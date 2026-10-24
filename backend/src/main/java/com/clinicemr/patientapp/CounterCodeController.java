package com.clinicemr.patientapp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.access.OtpCodes;
import com.clinicemr.patient.PatientService;
import com.clinicemr.shared.PhoneNumbers;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Free patient-app sign-in at the counter: staff show a one-time code (and QR) on screen, the patient scans it or
 * types it on their phone. Staff have just seen the patient in person, which is what the code vouches for.
 */
@RestController
public class CounterCodeController {

    static final Duration VALIDITY = Duration.ofMinutes(15);
    static final int MAX_PER_PATIENT_PER_HOUR = 10;

    private final JdbcClient jdbc;
    private final OtpCodes otp;
    private final PatientService patients;
    private final AuditService audit;

    public CounterCodeController(JdbcClient jdbc, OtpCodes otp, PatientService patients, AuditService audit) {
        this.jdbc = jdbc;
        this.otp = otp;
        this.patients = patients;
        this.audit = audit;
    }

    /** {@code code} is shown once, here; only its hash is stored. */
    public record CounterCode(UUID challengeId, String code, String phone, Instant expiresAt) {
    }

    @PostMapping("/api/v1/clinic/patients/{patientId}/app-sign-in-code")
    @Transactional
    public CounterCode create(ClinicContext ctx, @PathVariable UUID patientId) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        String phone = jdbc.sql("SELECT primary_phone_e164 FROM patient_profile WHERE id = :id")
                .param("id", profileId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (phone == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_PHONE", "Add the patient's mobile number first.");
        }
        int recent = jdbc.sql("""
                SELECT count(*) FROM otp_challenge
                 WHERE phone_e164 = :phone AND purpose = 'COUNTER_LOGIN' AND created_at > now() - interval '1 hour'
                """)
                .param("phone", phone)
                .query(Integer.class)
                .single();
        if (recent >= MAX_PER_PATIENT_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED", "Too many codes for this number. Try again later.");
        }
        // Only the newest code for the number works
        jdbc.sql("""
                UPDATE otp_challenge SET consumed_at = now()
                 WHERE phone_e164 = :phone AND purpose = 'COUNTER_LOGIN' AND consumed_at IS NULL
                """)
                .param("phone", phone)
                .update();
        UUID id = UUID.randomUUID();
        String code = otp.generate();
        Instant expiresAt = Instant.now().plus(VALIDITY);
        jdbc.sql("""
                INSERT INTO otp_challenge (id, phone_e164, purpose, code_hash, expires_at, issued_by_staff, clinic_id)
                VALUES (:id, :phone, 'COUNTER_LOGIN', :hash, :expiresAt, :staffId, :clinicId)
                """)
                .param("id", id)
                .param("phone", phone)
                .param("hash", otp.hash(code, id.toString()))
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .param("staffId", ctx.staffId())
                .param("clinicId", ctx.clinicId())
                .update();
        audit.record("STAFF", "staff:" + ctx.staffId(), ctx.clinicId(), "PATIENT_APP_CODE_ISSUED", "patient_profile",
                String.valueOf(profileId), Map.of());
        return new CounterCode(id, code, PhoneNumbers.mask(phone), expiresAt);
    }
}
