package com.clinicemr.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.clinicemr.patient.PatientDtos;
import com.clinicemr.patient.PatientService;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a patient paid for a visit and what is still to collect: total = paid + balance, in whole rupees. A balance
 * has a due date; from that day the visit's doctor sees it in "Money to collect". Any staff member of the clinic can
 * record or settle a payment.
 */
@Service
public class PaymentService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_AMOUNT = 10_000_000;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PatientService patients;

    public PaymentService(JdbcClient jdbc, AuditService audit, PatientService patients) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.patients = patients;
    }

    public record Payment(UUID encounterId, int paid, int balance, int total, LocalDate dueDate, Instant settledAt,
                          Instant updatedAt) {
    }

    /** {@code dueDate} is needed when there is a balance, and can't be in the past. */
    public record SavePayment(Integer paid, Integer balance, LocalDate dueDate, String comment) {
    }

    /** One balance still to collect. {@code overdueDays} is 0 on the due date, positive after it, negative before. */
    public record Due(UUID encounterId, UUID patientId, String name, String mrn, String gender, Integer ageYears,
                      String phone, int balance, int paid, LocalDate dueDate, int overdueDays, Instant visitAt,
                      String doctorName) {
    }

    public Optional<Payment> get(ClinicContext ctx, UUID encounterId) {
        visit(ctx, encounterId);
        return find(encounterId);
    }

    @Transactional
    public Payment save(ClinicContext ctx, UUID encounterId, SavePayment req) {
        Visit v = visit(ctx, encounterId);
        int paid = amount(req.paid(), "paid");
        int balance = amount(req.balance(), "balance");
        LocalDate due = null;
        if (balance > 0) {
            if (req.dueDate() == null) {
                throw ApiException.badRequest("DUE_DATE_REQUIRED", "Choose when the balance is due.");
            }
            if (req.dueDate().isBefore(today())) {
                throw ApiException.badRequest("DUE_DATE_PAST", "The due date can't be in the past.");
            }
            due = req.dueDate();
        }
        Optional<Payment> before = find(encounterId);
        jdbc.sql("""
                INSERT INTO visit_payment (encounter_id, clinic_id, profile_id, paid, balance, due_date, settled_at,
                                           recorded_by, updated_by)
                VALUES (:id, :clinicId, :profileId, :paid, :balance, :due, NULL, :staffId, :staffId)
                ON CONFLICT (encounter_id) DO UPDATE
                   SET paid = :paid, balance = :balance, due_date = :due, updated_by = :staffId, updated_at = now(),
                       settled_at = CASE WHEN :balance = 0 AND visit_payment.balance > 0 THEN now()
                                         WHEN :balance > 0 THEN NULL ELSE visit_payment.settled_at END
                """)
                .param("id", encounterId)
                .param("clinicId", ctx.clinicId())
                .param("profileId", v.profileId())
                .param("paid", paid)
                .param("balance", balance)
                .param("due", due)
                .param("staffId", ctx.staffId())
                .update();
        // The history records the change in what was paid: money at the visit, more later, or a correction
        int delta = paid - before.map(Payment::paid).orElse(0);
        if (delta != 0) {
            log(encounterId, ctx.clinicId(), v.profileId(), delta,
                    delta < 0 ? "CORRECTION" : before.isPresent() ? "BALANCE" : "AT_VISIT", null, ctx.staffId(),
                    comment(req.comment()));
        }
        audit.staff(ctx.staffId(), ctx.clinicId(), "PAYMENT_SAVED", "encounter", encounterId.toString(),
                Map.of("paid", paid, "balance", balance));
        return find(encounterId).orElseThrow();
    }

    /** The whole balance was received: it moves into paid. */
    @Transactional
    public Payment settle(ClinicContext ctx, UUID encounterId) {
        visit(ctx, encounterId);
        int balance = lockedBalance(encounterId);
        if (balance == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTHING_DUE", "There is no balance to collect for this visit.");
        }
        return receive(ctx, encounterId, balance, null);
    }

    /** Part (or all) of the balance was received: that much moves from balance into paid. */
    @Transactional
    public Payment receive(ClinicContext ctx, UUID encounterId, Integer amount, String comment) {
        Visit v = visit(ctx, encounterId);
        int balance = lockedBalance(encounterId);
        if (balance == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTHING_DUE", "There is no balance to collect for this visit.");
        }
        if (amount == null || amount < 1 || amount > balance) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter an amount from ₹1 to ₹" + balance + ".");
        }
        apply(encounterId, ctx.clinicId(), v.profileId(), amount, null, ctx.staffId(), comment(comment));
        audit.staff(ctx.staffId(), ctx.clinicId(), "PAYMENT_RECEIVED", "encounter", encounterId.toString(),
                Map.of("amount", amount));
        return find(encounterId).orElseThrow();
    }

    /** {@code appliedTo}: how one patient-level payment was split across visits, latest visit first. */
    public record Received(int amount, int remainingBalance, List<Applied> appliedTo) {
    }

    public record Applied(UUID encounterId, Instant visitAt, int amount, int balanceLeft) {
    }

    /**
     * A payment against everything the patient owes at this clinic. It can't be more than the total balance
     * ({@code full} takes exactly that). It is taken off the visits' balances in descending order, latest visit
     * first, until it runs out; the history keeps the whole payment together under one batch id.
     */
    @Transactional
    public Received receiveForPatient(ClinicContext ctx, UUID patientId, Integer amount, boolean full, String comment) {
        return receiveForProfile(ctx, patients.requireLinkedProfileId(ctx, patientId), amount, full, comment);
    }

    /** Same as {@link #receiveForPatient}, for a profile already known to belong to this clinic. */
    @Transactional
    public Received receiveForProfile(ClinicContext ctx, long profileId, Integer amount, boolean full, String comment) {
        record Open(UUID encounterId, Instant startedAt, int balance) {
        }
        List<Open> open = jdbc.sql("""
                SELECT vp.encounter_id, e.started_at, vp.balance
                  FROM visit_payment vp JOIN encounter e ON e.id = vp.encounter_id
                 WHERE vp.clinic_id = :clinicId AND vp.profile_id = :profileId AND vp.balance > 0 AND e.status <> 'CANCELLED'
                 ORDER BY e.started_at DESC
                   FOR UPDATE OF vp
                """)
                .param("clinicId", ctx.clinicId())
                .param("profileId", profileId)
                .query(Open.class)
                .list();
        int total = open.stream().mapToInt(Open::balance).sum();
        if (total == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTHING_DUE", "This patient has no balance to collect.");
        }
        int pay = full ? total : amount == null ? 0 : amount;
        if (pay < 1 || pay > total) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter an amount from ₹1 to ₹" + total + ".");
        }
        UUID batch = UUID.randomUUID();
        int left = pay;
        List<Applied> applied = new java.util.ArrayList<>();
        for (Open o : open) {
            if (left == 0) {
                break;
            }
            int part = Math.min(left, o.balance());
            apply(o.encounterId(), ctx.clinicId(), profileId, part, batch, ctx.staffId(), comment(comment));
            applied.add(new Applied(o.encounterId(), o.startedAt(), part, o.balance() - part));
            left -= part;
        }
        audit.staff(ctx.staffId(), ctx.clinicId(), "PATIENT_PAYMENT_RECEIVED", "patient_profile", String.valueOf(profileId),
                Map.of("amount", pay, "visits", applied.size()));
        return new Received(pay, total - pay, applied);
    }

    /** One line of a patient's payment history. Entries of one patient-level payment share {@code batchId}. */
    public record HistoryEntry(long id, Instant receivedAt, int amount, String kind, UUID batchId, UUID encounterId,
                               Instant visitAt, String recordedBy, String comment) {
    }

    /** Totals for the patient at this clinic, and every payment received, newest first. */
    public record History(int total, int paid, int balance, List<HistoryEntry> entries) {
    }

    public History history(ClinicContext ctx, UUID patientId) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        record Totals(int paid, int balance) {
        }
        Totals t = jdbc.sql("""
                SELECT coalesce(sum(vp.paid), 0)::int AS paid, coalesce(sum(vp.balance), 0)::int AS balance
                  FROM visit_payment vp JOIN encounter e ON e.id = vp.encounter_id
                 WHERE vp.clinic_id = :clinicId AND vp.profile_id = :profileId AND e.status <> 'CANCELLED'
                """)
                .param("clinicId", ctx.clinicId())
                .param("profileId", profileId)
                .query(Totals.class)
                .single();
        List<HistoryEntry> entries = jdbc.sql("""
                SELECT pe.id, pe.received_at, pe.amount, pe.kind, pe.batch_id, pe.encounter_id,
                       e.started_at AS visit_at, s.name AS recorded_by, pe.comment
                  FROM payment_entry pe
                  JOIN encounter e ON e.id = pe.encounter_id
                  JOIN staff_user s ON s.id = pe.recorded_by
                 WHERE pe.clinic_id = :clinicId AND pe.profile_id = :profileId
                 ORDER BY pe.received_at DESC, pe.id
                 LIMIT 500
                """)
                .param("clinicId", ctx.clinicId())
                .param("profileId", profileId)
                .query(HistoryEntry.class)
                .list();
        return new History(t.paid() + t.balance(), t.paid(), t.balance(), entries);
    }

    /** Moves {@code amount} from balance into paid and writes it to the history. The row must be locked. */
    private void apply(UUID encounterId, long clinicId, long profileId, int amount, UUID batch, long staffId, String comment) {
        jdbc.sql("""
                UPDATE visit_payment
                   SET paid = paid + :amount, balance = balance - :amount,
                       due_date = CASE WHEN balance - :amount = 0 THEN NULL ELSE due_date END,
                       settled_at = CASE WHEN balance - :amount = 0 THEN now() ELSE settled_at END,
                       updated_by = :staffId, updated_at = now()
                 WHERE encounter_id = :id
                """)
                .param("amount", amount)
                .param("id", encounterId)
                .param("staffId", staffId)
                .update();
        log(encounterId, clinicId, profileId, amount, "BALANCE", batch, staffId, comment);
    }

    private void log(UUID encounterId, long clinicId, long profileId, int amount, String kind, UUID batch, long staffId,
                     String comment) {
        jdbc.sql("""
                INSERT INTO payment_entry (encounter_id, clinic_id, profile_id, amount, kind, batch_id, recorded_by, comment)
                VALUES (:id, :clinicId, :profileId, :amount, :kind, :batch, :staffId, :comment)
                """)
                .param("comment", comment)
                .param("id", encounterId)
                .param("clinicId", clinicId)
                .param("profileId", profileId)
                .param("amount", amount)
                .param("kind", kind)
                .param("batch", batch)
                .param("staffId", staffId)
                .update();
    }

    /** An optional comment, trimmed to 300 characters; blank means none. */
    static String comment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    private int lockedBalance(UUID encounterId) {
        return jdbc.sql("SELECT balance FROM visit_payment WHERE encounter_id = :id FOR UPDATE")
                .param("id", encounterId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /**
     * Balances still to collect, soonest first. {@code dueNow} keeps those due today or earlier (the reminder);
     * {@code mine} keeps the signed-in doctor's own visits; {@code patientId} keeps one patient's.
     */
    public List<Due> dues(ClinicContext ctx, boolean dueNow, boolean mine, UUID patientId) {
        record Row(UUID encounterId, UUID publicId, String name, String mrn, String gender, LocalDate dob,
                   String primaryPhoneE164, int balance, int paid, LocalDate dueDate, Instant startedAt, String doctorName) {
        }
        LocalDate today = today();
        return jdbc.sql("""
                SELECT vp.encounter_id, p.public_id, p.name, pi.mrn, p.gender, p.dob, p.primary_phone_e164,
                       vp.balance, vp.paid, vp.due_date, e.started_at, s.name AS doctor_name
                  FROM visit_payment vp
                  JOIN encounter e ON e.id = vp.encounter_id
                  JOIN patient_profile p ON p.id = vp.profile_id
                  LEFT JOIN patient_identifier pi ON pi.profile_id = p.id AND pi.clinic_id = vp.clinic_id
                  JOIN staff_user s ON s.id = e.doctor_id
                 WHERE vp.clinic_id = :clinicId AND vp.balance > 0 AND e.status <> 'CANCELLED' AND p.deleted_at IS NULL
                   AND (NOT :dueNow OR vp.due_date <= :today)
                   AND (NOT :mine OR e.doctor_id = :staffId)
                   AND (CAST(:patientId AS uuid) IS NULL OR p.public_id = CAST(:patientId AS uuid))
                 ORDER BY vp.due_date, e.started_at
                 LIMIT 300
                """)
                .param("clinicId", ctx.clinicId())
                .param("dueNow", dueNow)
                .param("today", today)
                .param("mine", mine)
                .param("staffId", ctx.staffId())
                .param("patientId", patientId)
                .query(Row.class)
                .list().stream()
                .map(r -> new Due(r.encounterId(), r.publicId(), r.name(), r.mrn(), r.gender(), PatientDtos.age(r.dob()),
                        r.primaryPhoneE164() == null ? null : r.primaryPhoneE164().substring(3), r.balance(), r.paid(),
                        r.dueDate(), (int) java.time.temporal.ChronoUnit.DAYS.between(r.dueDate(), today),
                        r.startedAt(), r.doctorName()))
                .toList();
    }

    private Optional<Payment> find(UUID encounterId) {
        record Row(UUID encounterId, int paid, int balance, LocalDate dueDate, Instant settledAt, Instant updatedAt) {
        }
        return jdbc.sql("SELECT encounter_id, paid, balance, due_date, settled_at, updated_at FROM visit_payment WHERE encounter_id = :id")
                .param("id", encounterId)
                .query(Row.class)
                .optional()
                .map(r -> new Payment(r.encounterId(), r.paid(), r.balance(), r.paid() + r.balance(), r.dueDate(),
                        r.settledAt(), r.updatedAt()));
    }

    private record Visit(long profileId) {
    }

    private Visit visit(ClinicContext ctx, UUID encounterId) {
        return jdbc.sql("SELECT profile_id FROM encounter WHERE id = :id AND clinic_id = :clinicId AND status <> 'CANCELLED'")
                .param("id", encounterId)
                .param("clinicId", ctx.clinicId())
                .query(Visit.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Visit"));
    }

    private static int amount(Integer value, String field) {
        int v = value == null ? 0 : value;
        if (v < 0 || v > MAX_AMOUNT) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter " + field + " as rupees, 0 or more.");
        }
        return v;
    }

    private static LocalDate today() {
        return LocalDate.now(IST);
    }
}
