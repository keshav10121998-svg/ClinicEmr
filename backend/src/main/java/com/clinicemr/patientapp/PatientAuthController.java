package com.clinicemr.patientapp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.access.OtpCodes;
import com.clinicemr.notification.Notifier;
import com.clinicemr.shared.PhoneNumbers;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patient sign-in with a one-time code sent to their phone. On first sign-in, every profile registered with that
 * number at any clinic is linked to the account as its owner.
 */
@RestController
@RequestMapping("/api/v1/auth/patient")
public class PatientAuthController {

    static final Duration OTP_VALIDITY = Duration.ofMinutes(10);
    static final int MAX_CODES_PER_PHONE_PER_HOUR = 5;

    private final JdbcClient jdbc;
    private final OtpCodes otp;
    private final Notifier notifier;
    private final TokenService tokens;
    private final AuditService audit;

    public PatientAuthController(JdbcClient jdbc, OtpCodes otp, Notifier notifier, TokenService tokens, AuditService audit) {
        this.jdbc = jdbc;
        this.otp = otp;
        this.notifier = notifier;
        this.tokens = tokens;
        this.audit = audit;
    }

    public record CodeRequest(@NotBlank String phone) {
    }

    public record CodeSent(UUID challengeId, String phone, Instant expiresAt) {
    }

    public record VerifyCode(@NotNull UUID challengeId,
                             @NotNull @Pattern(regexp = "\\d{6}", message = "must be the 6-digit code") String otp) {
    }

    public record SignedIn(String accessToken, Instant expiresAt, long accountId, int profiles) {
    }

    @PostMapping("/otp")
    @Transactional
    public CodeSent requestCode(@Valid @RequestBody CodeRequest req) {
        String phone = PhoneNumbers.toE164(req.phone());
        int recent = jdbc.sql("""
                SELECT count(*) FROM otp_challenge WHERE phone_e164 = :phone AND created_at > now() - interval '1 hour'
                """)
                .param("phone", phone)
                .query(Integer.class)
                .single();
        if (recent >= MAX_CODES_PER_PHONE_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED", "Too many codes requested. Try again in an hour.");
        }
        UUID id = UUID.randomUUID();
        String code = otp.generate();
        Instant expiresAt = Instant.now().plus(OTP_VALIDITY);
        jdbc.sql("""
                INSERT INTO otp_challenge (id, phone_e164, purpose, code_hash, expires_at)
                VALUES (:id, :phone, 'PATIENT_LOGIN', :hash, :expiresAt)
                """)
                .param("id", id)
                .param("phone", phone)
                .param("hash", otp.hash(code, id.toString()))
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();
        notifier.smsAfterCommit(phone, code + " is your ClinicEMR sign-in code. Valid 10 minutes. Don't share it with anyone.");
        return new CodeSent(id, PhoneNumbers.mask(phone), expiresAt);
    }

    @PostMapping("/otp/verify")
    @Transactional(noRollbackFor = ApiException.class)
    public SignedIn verify(@Valid @RequestBody VerifyCode req) {
        record Challenge(String phoneE164, String codeHash, int attempts, Instant expiresAt, Instant consumedAt) {
        }
        Challenge c = jdbc.sql("""
                SELECT phone_e164, code_hash, attempts, expires_at, consumed_at FROM otp_challenge WHERE id = :id FOR UPDATE
                """)
                .param("id", req.challengeId())
                .query(Challenge.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Sign-in code"));
        if (c.consumedAt() != null || Instant.now().isAfter(c.expiresAt()) || c.attempts() >= OtpCodes.MAX_ATTEMPTS) {
            throw new ApiException(HttpStatus.GONE, "OTP_EXPIRED", "This code can't be used any more. Request a new one.");
        }
        if (!otp.matches(req.otp(), req.challengeId().toString(), c.codeHash())) {
            jdbc.sql("UPDATE otp_challenge SET attempts = attempts + 1 WHERE id = :id").param("id", req.challengeId()).update();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "OTP_INVALID", "That code is not right.",
                    Map.of("attemptsLeft", Math.max(0, OtpCodes.MAX_ATTEMPTS - c.attempts() - 1)));
        }
        jdbc.sql("UPDATE otp_challenge SET consumed_at = now() WHERE id = :id").param("id", req.challengeId()).update();

        long accountId = jdbc.sql("""
                INSERT INTO account (phone_e164, last_login_at) VALUES (:phone, now())
                ON CONFLICT (phone_e164) DO UPDATE SET last_login_at = now()
                RETURNING id
                """)
                .param("phone", c.phoneE164())
                .query(Long.class)
                .single();
        // Profiles registered at clinics with this number become the account's own
        jdbc.sql("""
                INSERT INTO profile_access (account_id, profile_id, role)
                SELECT :accountId, p.id, 'OWNER' FROM patient_profile p
                 WHERE p.primary_phone_e164 = :phone AND p.deleted_at IS NULL AND p.merged_into_id IS NULL
                ON CONFLICT DO NOTHING
                """)
                .param("accountId", accountId)
                .param("phone", c.phoneE164())
                .update();
        jdbc.sql("""
                UPDATE patient_profile SET claimed = true
                 WHERE id IN (SELECT profile_id FROM profile_access WHERE account_id = :accountId) AND NOT claimed
                """)
                .param("accountId", accountId)
                .update();
        int profiles = jdbc.sql("SELECT count(*) FROM profile_access WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(Integer.class)
                .single();
        audit.record("PATIENT", "account:" + accountId, null, "PATIENT_SIGNED_IN", "account", String.valueOf(accountId), Map.of());
        TokenService.IssuedToken token = tokens.issuePatientToken(accountId, c.phoneE164());
        return new SignedIn(token.value(), token.expiresAt(), accountId, profiles);
    }
}
