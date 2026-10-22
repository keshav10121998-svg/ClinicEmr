package com.clinicemr.access;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.clinicemr.access.AccessDtos.AccessRequestView;
import com.clinicemr.access.AccessDtos.ClinicGrantRequest;
import com.clinicemr.access.AccessDtos.SharedProfile;
import com.clinicemr.access.AccessDtos.StartAccessRequest;
import com.clinicemr.notification.Notifier;
import com.clinicemr.patient.PatientService;
import com.clinicemr.shared.PhoneNumbers;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The counter flow: staff enter a phone number (or pick a patient), the patient approves by reading out the OTP
 * or tapping Approve in the app, staff pick the person and what to share.
 * <p>
 * Nothing about the number is revealed until the patient approves: the response is the same whether or not
 * anyone with that number is registered.
 */
@Service
public class AccessRequestService {

    static final Duration OTP_VALIDITY = Duration.ofMinutes(10);
    static final Duration GRANT_WINDOW = Duration.ofMinutes(15);
    static final int MAX_REQUESTS_PER_STAFF_PER_HOUR = 30;
    static final int MAX_REQUESTS_PER_PHONE_PER_HOUR = 5;

    private final JdbcClient jdbc;
    private final OtpCodes otp;
    private final Notifier notifier;
    private final ProfileDirectory directory;
    private final GrantService grants;
    private final PatientService patients;
    private final AuditService audit;

    public AccessRequestService(JdbcClient jdbc, OtpCodes otp, Notifier notifier, ProfileDirectory directory,
                                GrantService grants, PatientService patients, AuditService audit) {
        this.jdbc = jdbc;
        this.otp = otp;
        this.notifier = notifier;
        this.directory = directory;
        this.grants = grants;
        this.patients = patients;
        this.audit = audit;
    }

    record RequestRow(UUID id, long clinicId, long staffId, String phoneE164, String otpHash, int attempts,
                      String status, Instant verifiedAt, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    @Transactional
    public AccessRequestView start(ClinicContext ctx, StartAccessRequest req) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE, ClinicRole.FRONT_DESK);
        String phone;
        if (req.patientId() != null) {
            phone = patients.get(ctx, req.patientId()).phone();
            if (phone == null) {
                throw ApiException.badRequest("NO_PHONE", "This patient has no phone number to approve with.");
            }
        } else if (req.phone() != null && !req.phone().isBlank()) {
            phone = PhoneNumbers.toE164(req.phone());
        } else {
            throw ApiException.badRequest("PHONE_OR_PATIENT_REQUIRED", "Enter the patient's phone number or choose a patient.");
        }

        int byStaff = countSince("staff_id = :v", ctx.staffId());
        int byPhone = countSince("phone_e164 = :v", phone);
        if (byStaff >= MAX_REQUESTS_PER_STAFF_PER_HOUR || byPhone >= MAX_REQUESTS_PER_PHONE_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOOKUP_RATE_LIMITED",
                    "Too many access requests. Try again in an hour.");
        }

        UUID id = UUID.randomUUID();
        String code = otp.generate();
        jdbc.sql("""
                INSERT INTO access_request (id, clinic_id, staff_id, phone_e164, otp_hash, expires_at)
                VALUES (:id, :clinicId, :staffId, :phone, :hash, :expiresAt)
                """)
                .param("id", id)
                .param("clinicId", ctx.clinicId())
                .param("staffId", ctx.staffId())
                .param("phone", phone)
                .param("hash", otp.hash(code, id.toString()))
                .param("expiresAt", java.sql.Timestamp.from(Instant.now().plus(OTP_VALIDITY)))
                .update();

        String clinicName = jdbc.sql("SELECT name FROM clinic WHERE id = :id").param("id", ctx.clinicId())
                .query(String.class).single();
        notifier.smsAfterCommit(phone, code + " is the code to let " + clinicName
                + " view your family's medical records. Share it only with the clinic staff in front of you. "
                + "Valid 10 minutes. You can also approve in the ClinicEMR app.");
        audit.staff(ctx.staffId(), ctx.clinicId(), "ACCESS_REQUESTED", "access_request", id.toString(),
                Map.of("phone", PhoneNumbers.mask(phone)));
        return view(ctx, load(ctx, id));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AccessRequestView verify(ClinicContext ctx, UUID id, String code) {
        RequestRow r = loadForUpdate(ctx, id);
        if (!"PENDING".equals(r.status())) {
            return view(ctx, r);   // already verified, approved in the app, or closed
        }
        if (r.expired()) {
            throw new ApiException(HttpStatus.GONE, "REQUEST_EXPIRED", "This code has expired. Start a new request.");
        }
        if (!otp.matches(code, id.toString(), r.otpHash())) {
            int attempts = r.attempts() + 1;
            boolean locked = attempts >= OtpCodes.MAX_ATTEMPTS;
            jdbc.sql("UPDATE access_request SET attempts = :a, status = :s WHERE id = :id")
                    .param("a", attempts)
                    .param("s", locked ? "LOCKED" : "PENDING")
                    .param("id", id)
                    .update();
            throw new ApiException(HttpStatus.UNAUTHORIZED, locked ? "OTP_LOCKED" : "OTP_INVALID",
                    locked ? "Too many wrong codes. Start a new request." : "That code is not right.",
                    Map.of("attemptsLeft", Math.max(0, OtpCodes.MAX_ATTEMPTS - attempts)));
        }
        jdbc.sql("""
                UPDATE access_request SET status = 'VERIFIED', verified_at = now(), expires_at = :expiresAt WHERE id = :id
                """)
                .param("expiresAt", java.sql.Timestamp.from(Instant.now().plus(GRANT_WINDOW)))
                .param("id", id)
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "ACCESS_VERIFIED", "access_request", id.toString(), Map.of());
        return view(ctx, load(ctx, id));
    }

    public AccessRequestView get(ClinicContext ctx, UUID id) {
        return view(ctx, load(ctx, id));
    }

    /**
     * After the patient approved by OTP: create a grant for one person on that number, linking them to this clinic
     * if they're new here.
     */
    @Transactional
    public AccessRequestView grant(ClinicContext ctx, UUID id, ClinicGrantRequest req) {
        RequestRow r = loadForUpdate(ctx, id);
        if (!"VERIFIED".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_VERIFIED", "Enter the patient's code first.");
        }
        if (r.expired()) {
            throw new ApiException(HttpStatus.GONE, "REQUEST_EXPIRED", "This approval has expired. Start a new request.");
        }
        long profileId = directory.forPhone(r.phoneE164(), ctx.clinicId()).stream()
                .filter(p -> p.publicId().equals(req.profileId()))
                .findFirst()
                .orElseThrow(() -> ApiException.forbidden("NOT_ON_THIS_NUMBER", "This person isn't on the approving number."))
                .id();
        Long doctorId = null;
        if (req.onlyMe()) {
            ctx.require(ClinicRole.DOCTOR);
            doctorId = ctx.staffId();
        }
        patients.linkToClinic(ctx, profileId);
        UUID grantId = grants.create(profileId, ctx.clinicId(), doctorId, req.scope(), r.phoneE164(), null, "OTP", id);
        audit.staff(ctx.staffId(), ctx.clinicId(), "ACCESS_GRANTED", "record_access_grant", grantId.toString(),
                Map.of("profileId", profileId, "scope", req.scope().types(), "duration", req.scope().duration().name()));
        return view(ctx, load(ctx, id));
    }

    private AccessRequestView view(ClinicContext ctx, RequestRow r) {
        boolean approved = "VERIFIED".equals(r.status()) || "APPROVED".equals(r.status());
        List<SharedProfile> profiles = approved ? directory.sharedProfiles(r.phoneE164(), ctx.clinicId()) : List.of();
        String next = switch (r.status()) {
            case "PENDING" -> r.expired() ? "Expired. Start a new request."
                    : "Ask the patient for the code sent to their phone, or wait for them to approve in the app.";
            case "VERIFIED" -> r.expired() ? "Expired. Start a new request." : "Choose the person and what to share.";
            case "APPROVED" -> "Approved in the app.";
            case "DENIED" -> "The patient declined.";
            default -> "Too many wrong codes. Start a new request.";
        };
        return new AccessRequestView(r.id(), PhoneNumbers.mask(r.phoneE164()), r.status(), r.expiresAt(), profiles,
                grants.activeForRequest(r.id()), next);
    }

    private RequestRow load(ClinicContext ctx, UUID id) {
        return find(ctx, id, false).orElseThrow(() -> ApiException.notFound("Access request"));
    }

    private RequestRow loadForUpdate(ClinicContext ctx, UUID id) {
        return find(ctx, id, true).orElseThrow(() -> ApiException.notFound("Access request"));
    }

    private Optional<RequestRow> find(ClinicContext ctx, UUID id, boolean forUpdate) {
        // Only the staff member who started a request can continue it
        return jdbc.sql("""
                SELECT id, clinic_id, staff_id, phone_e164, otp_hash, attempts, status, verified_at, expires_at
                  FROM access_request WHERE id = :id AND clinic_id = :clinicId AND staff_id = :staffId
                """ + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .param("clinicId", ctx.clinicId())
                .param("staffId", ctx.staffId())
                .query(RequestRow.class)
                .optional();
    }

    private int countSince(String condition, Object value) {
        return jdbc.sql("SELECT count(*) FROM access_request WHERE " + condition + " AND created_at > now() - interval '1 hour'")
                .param("v", value)
                .query(Integer.class)
                .single();
    }
}
