package com.clinicemr.billing;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.patient.PatientService;
import com.clinicemr.patientapp.PatientRecordsService;
import com.clinicemr.records.FileSignatures;
import com.clinicemr.records.storage.ObjectStorage;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import com.clinicemr.shared.security.PatientContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * A patient who paid outside the clinic (UPI, bank transfer) sends the amount and a screenshot; the clinic's doctor
 * approves it, which records the payment exactly like "Received" (taken off the latest visit first), or rejects it
 * with a reason. A patient can't ask for more than they owe that clinic, counting requests still waiting.
 */
@Service
public class PaymentRequestService {

    private static final long MAX_PROOF_BYTES = 8L * 1024 * 1024;
    private static final Map<String, String> PROOF_TYPES = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/heic", "heic", "application/pdf", "pdf");

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final PaymentService payments;
    private final PatientRecordsService records;
    private final PatientService patients;
    private final AuditService audit;

    public PaymentRequestService(JdbcClient jdbc, ObjectStorage storage, PaymentService payments,
                                 PatientRecordsService records, PatientService patients, AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.payments = payments;
        this.records = records;
        this.patients = patients;
        this.audit = audit;
    }

    /** What the patient owes one clinic. {@code pending}: amounts already sent for approval. */
    public record ClinicBalance(long clinicId, String clinicName, int balance, int pending, LocalDate nextDue) {
    }

    /** {@code proofUrl} is a short-lived link to the screenshot (clinic side only). */
    public record Request(UUID id, long clinicId, String clinicName, UUID patientId, String patientName, String mrn,
                          int amount, String note, String status, Integer approvedAmount, String rejectReason,
                          Instant createdAt, Instant decidedAt, String decidedBy, String decisionComment, String proofUrl, String proofType) {
    }

    // ------------------------------------------------------------------ patient side

    public List<ClinicBalance> balances(PatientContext p, UUID profileId) {
        long profile = records.requireProfile(p, profileId);
        return jdbc.sql("""
                SELECT c.id AS clinic_id, c.name AS clinic_name, sum(vp.balance)::int AS balance,
                       coalesce((SELECT sum(r.amount) FROM payment_request r
                                  WHERE r.clinic_id = c.id AND r.profile_id = :profile AND r.status = 'PENDING'), 0)::int AS pending,
                       min(vp.due_date) AS next_due
                  FROM visit_payment vp
                  JOIN encounter e ON e.id = vp.encounter_id
                  JOIN clinic c ON c.id = vp.clinic_id
                 WHERE vp.profile_id = :profile AND vp.balance > 0 AND e.status <> 'CANCELLED'
                 GROUP BY c.id, c.name
                 ORDER BY c.name
                """)
                .param("profile", profile)
                .query(ClinicBalance.class)
                .list();
    }

    @Transactional
    public Request submit(PatientContext p, UUID profileId, long clinicId, Integer amount, String note, MultipartFile proof)
            throws IOException {
        long profile = records.requireProfile(p, profileId);
        ClinicBalance owed = balances(p, profileId).stream().filter(b -> b.clinicId() == clinicId).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOTHING_DUE", "Nothing is due to this clinic."));
        int open = owed.balance() - owed.pending();
        if (open <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SENT",
                    "You've already sent proof for everything you owe this clinic. It's waiting for the clinic to check.");
        }
        if (amount == null || amount < 1 || amount > open) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter an amount from ₹1 to ₹" + open + ".");
        }
        if (proof == null || proof.isEmpty()) {
            throw ApiException.badRequest("PROOF_REQUIRED", "Add a screenshot of the payment.");
        }
        if (proof.getSize() > MAX_PROOF_BYTES) {
            throw ApiException.badRequest("PROOF_TOO_BIG", "The screenshot must be 8 MB or smaller.");
        }
        String type = proof.getContentType() == null ? "" : proof.getContentType().toLowerCase(Locale.ROOT);
        byte[] bytes = proof.getBytes();
        if (!PROOF_TYPES.containsKey(type) || !FileSignatures.matches(type, bytes)) {
            throw ApiException.badRequest("PROOF_TYPE", "Send a photo or screenshot (JPG, PNG, WEBP, HEIC) or a PDF.");
        }
        UUID id = UUID.randomUUID();
        String key = "payment-proofs/" + id + "." + PROOF_TYPES.get(type);
        storage.put(key, type, bytes);
        jdbc.sql("""
                INSERT INTO payment_request (id, clinic_id, profile_id, account_id, amount, note, proof_key, proof_type)
                VALUES (:id, :clinicId, :profile, :accountId, :amount, :note, :key, :type)
                """)
                .param("id", id)
                .param("clinicId", clinicId)
                .param("profile", profile)
                .param("accountId", p.accountId())
                .param("amount", amount)
                .param("note", note == null || note.isBlank() ? null : note.trim().substring(0, Math.min(note.trim().length(), 300)))
                .param("key", key)
                .param("type", type)
                .update();
        audit.record("PATIENT", "account:" + p.accountId(), clinicId, "PAYMENT_PROOF_SENT", "payment_request", id.toString(),
                Map.of("amount", amount));
        return find(id, false).orElseThrow();
    }

    public List<Request> forPatient(PatientContext p, UUID profileId) {
        long profile = records.requireProfile(p, profileId);
        return list("r.profile_id = :profile", Map.of("profile", profile), false);
    }

    // ------------------------------------------------------------------ clinic side

    /** {@code status}: PENDING (default), APPROVED, REJECTED or ALL; optionally one patient's. */
    public List<Request> forClinic(ClinicContext ctx, String status, UUID patientId) {
        String s = status == null ? "PENDING" : status.toUpperCase(Locale.ROOT);
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("clinicId", ctx.clinicId());
        String where = "r.clinic_id = :clinicId";
        if (!"ALL".equals(s)) {
            where += " AND r.status = :status";
            params.put("status", s);
        }
        if (patientId != null) {
            where += " AND r.profile_id = :profile";
            params.put("profile", patients.requireLinkedProfileId(ctx, patientId));
        }
        return list(where, params, true);
    }

    /** Records the payment (up to what is still owed) and marks the request approved. Doctors and admins only. */
    @Transactional
    public PaymentService.Received approve(ClinicContext ctx, UUID id, String comment) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.ADMIN);
        Pending r = pending(ctx, id);
        int owed = jdbc.sql("""
                SELECT coalesce(sum(vp.balance), 0)::int FROM visit_payment vp JOIN encounter e ON e.id = vp.encounter_id
                 WHERE vp.clinic_id = :clinicId AND vp.profile_id = :profile AND e.status <> 'CANCELLED'
                """)
                .param("clinicId", ctx.clinicId())
                .param("profile", r.profileId())
                .query(Integer.class)
                .single();
        if (owed == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTHING_DUE",
                    "This patient owes nothing now, so there is nothing to approve. Reject the request instead.");
        }
        int amount = Math.min(r.amount(), owed);
        String note = PaymentService.comment(comment);
        PaymentService.Received received = payments.receiveForProfile(ctx, r.profileId(), amount, false,
                note == null ? "Payment proof approved" : "Payment proof approved: " + note);
        jdbc.sql("""
                UPDATE payment_request SET status = 'APPROVED', approved_amount = :amount, decision_comment = :comment,
                                           decided_by = :staffId, decided_at = now()
                 WHERE id = :id
                """)
                .param("comment", note)
                .param("amount", amount)
                .param("staffId", ctx.staffId())
                .param("id", id)
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "PAYMENT_PROOF_APPROVED", "payment_request", id.toString(),
                Map.of("amount", amount));
        return received;
    }

    @Transactional
    public Request reject(ClinicContext ctx, UUID id, String reason) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.ADMIN);
        pending(ctx, id);
        if (reason == null || reason.trim().length() < 3) {
            throw ApiException.badRequest("REASON_REQUIRED", "Say why, so the patient knows what to do (e.g. \"Payment not received\").");
        }
        jdbc.sql("""
                UPDATE payment_request SET status = 'REJECTED', reject_reason = :reason, decided_by = :staffId, decided_at = now()
                 WHERE id = :id
                """)
                .param("reason", reason.trim().substring(0, Math.min(reason.trim().length(), 300)))
                .param("staffId", ctx.staffId())
                .param("id", id)
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "PAYMENT_PROOF_REJECTED", "payment_request", id.toString(), Map.of());
        return find(id, true).orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    private record Pending(long profileId, int amount) {
    }

    private Pending pending(ClinicContext ctx, UUID id) {
        record Row(long profileId, int amount, String status) {
        }
        Row r = jdbc.sql("SELECT profile_id, amount, status FROM payment_request WHERE id = :id AND clinic_id = :clinicId FOR UPDATE")
                .param("id", id)
                .param("clinicId", ctx.clinicId())
                .query(Row.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Payment request"));
        if (!"PENDING".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_DECIDED", "This request was already " + r.status().toLowerCase(Locale.ROOT) + ".");
        }
        return new Pending(r.profileId(), r.amount());
    }

    private java.util.Optional<Request> find(UUID id, boolean withProof) {
        return list("r.id = :id", Map.of("id", id), withProof).stream().findFirst();
    }

    private List<Request> list(String where, Map<String, ?> params, boolean withProof) {
        record Row(UUID id, long clinicId, String clinicName, UUID publicId, String name, String mrn, int amount, String note,
                   String status, Integer approvedAmount, String rejectReason, Instant createdAt, Instant decidedAt,
                   String decidedBy, String decisionComment, String proofKey, String proofType) {
        }
        var q = jdbc.sql("""
                SELECT r.id, r.clinic_id, c.name AS clinic_name, p.public_id, p.name, pi.mrn, r.amount, r.note, r.status,
                       r.approved_amount, r.reject_reason, r.created_at, r.decided_at, s.name AS decided_by,
                       r.decision_comment, r.proof_key, r.proof_type
                  FROM payment_request r
                  JOIN clinic c ON c.id = r.clinic_id
                  JOIN patient_profile p ON p.id = r.profile_id
                  LEFT JOIN patient_identifier pi ON pi.profile_id = p.id AND pi.clinic_id = r.clinic_id
                  LEFT JOIN staff_user s ON s.id = r.decided_by
                 WHERE %s
                 ORDER BY r.created_at DESC
                 LIMIT 200
                """.formatted(where));
        for (var e : params.entrySet()) {
            q = q.param(e.getKey(), e.getValue());
        }
        return q.query(Row.class).list().stream()
                .map(r -> new Request(r.id(), r.clinicId(), r.clinicName(), r.publicId(), r.name(), r.mrn(), r.amount(), r.note(),
                        r.status(), r.approvedAmount(), r.rejectReason(), r.createdAt(), r.decidedAt(), r.decidedBy(), r.decisionComment(),
                        withProof ? storage.presignDownload(r.proofKey()).toString() : null, withProof ? r.proofType() : null))
                .toList();
    }
}
