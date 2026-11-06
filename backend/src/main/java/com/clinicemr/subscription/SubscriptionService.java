package com.clinicemr.subscription;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.AdminContext;
import com.clinicemr.subscription.SubscriptionPlan.Stage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each clinic pays and until when. Payments are collected outside the app and recorded here by the super admin;
 * nothing is blocked when a clinic is behind, the screens just say so. The price is worked out from the clinic's
 * start date and its current staff, so it needs no scheduled job.
 */
@Service
public class SubscriptionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_AMOUNT = 100_000;

    public enum Status { FREE_MONTH, PAID, DUE }

    /** The next change in price: the day it happens and what the base price becomes (staff charges are extra). */
    public record PriceChange(LocalDate on, int basePrice, int daysAway) {
    }

    /**
     * {@code total} is this month's price. {@code coveredUntil} is the first day not yet paid for (the free month
     * counts as paid); {@code nextAmount} is what the clinic pays to continue from that day.
     */
    public record Plan(long clinicId, String clinicName, LocalDate startedOn, int monthNumber, Stage stage, int basePrice,
                       int billableStaff, int staffCharge, int total, LocalDate freeUntil, LocalDate paidUntil,
                       LocalDate coveredUntil, Status status, int daysLeft, int nextAmount, PriceChange nextChange) {
    }

    /** A clinic as the super admin sees it, with who to contact about the payment. */
    public record ClinicPlan(Plan plan, String city, String ownerName, String ownerPhone, String ownerEmail, int payments) {
    }

    public record Overview(int clinics, int freeMonth, int paid, int due, int monthlyIncome, List<ClinicPlan> items) {
    }

    public record Payment(long id, int amount, int months, LocalDate coversFrom, LocalDate coversUntil, String note,
                          Instant recordedAt) {
    }

    private final JdbcClient jdbc;
    private final AuditService audit;

    public SubscriptionService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** One clinic's plan, for its own admin. */
    public Plan plan(long clinicId) {
        return plans("c.id = :id", Map.of("id", clinicId)).stream().findFirst()
                .map(ClinicPlan::plan).orElseThrow(() -> ApiException.notFound("Clinic"));
    }

    /** Every clinic, those that need to pay first, then by the day their payment runs out. */
    public Overview overview() {
        List<ClinicPlan> all = plans("TRUE", Map.of()).stream()
                .sorted(java.util.Comparator.comparing((ClinicPlan c) -> c.plan().status() != Status.DUE)
                        .thenComparing(c -> c.plan().coveredUntil())
                        .thenComparing(c -> c.plan().clinicName()))
                .toList();
        int income = all.stream().filter(c -> c.plan().stage() != Stage.FREE_MONTH).mapToInt(c -> c.plan().total()).sum();
        return new Overview(all.size(), count(all, Status.FREE_MONTH), count(all, Status.PAID), count(all, Status.DUE), income, all);
    }

    public List<Payment> payments(long clinicId) {
        return jdbc.sql("""
                SELECT id, amount, months, covers_from, covers_until, note, recorded_at
                  FROM subscription_payment WHERE clinic_id = :id ORDER BY recorded_at DESC, id DESC LIMIT 100
                """)
                .param("id", clinicId)
                .query(Payment.class)
                .list();
    }

    /**
     * A payment received for {@code months} months. It extends the clinic from the end of what it already paid for;
     * a clinic that was behind is covered from today, not from the day it fell behind.
     */
    @Transactional
    public Plan recordPayment(AdminContext admin, long clinicId, Integer amount, Integer months, String note) {
        if (amount == null || amount < 0 || amount > MAX_AMOUNT) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter the amount received, in rupees (0 to " + MAX_AMOUNT + ").");
        }
        if (months == null || months < 1 || months > 12) {
            throw ApiException.badRequest("INVALID_MONTHS", "Choose how many months it pays for (1 to 12).");
        }
        Plan before = plan(clinicId);
        LocalDate today = today();
        LocalDate from = before.coveredUntil().isBefore(today) ? today : before.coveredUntil();
        LocalDate until = from.plusMonths(months);
        String text = note == null || note.isBlank() ? null : note.trim().substring(0, Math.min(note.trim().length(), 300));
        jdbc.sql("""
                INSERT INTO subscription_payment (clinic_id, amount, months, covers_from, covers_until, note, recorded_by)
                VALUES (:id, :amount, :months, :from, :until, :note, :admin)
                """)
                .param("id", clinicId).param("amount", amount).param("months", months)
                .param("from", from).param("until", until).param("note", text).param("admin", admin.adminId())
                .update();
        jdbc.sql("UPDATE clinic SET paid_until = :until WHERE id = :id").param("until", until).param("id", clinicId).update();
        audit.record("ADMIN", String.valueOf(admin.adminId()), clinicId, "SUBSCRIPTION_PAYMENT_RECORDED", "clinic",
                String.valueOf(clinicId), Map.of("amount", amount, "months", months));
        return plan(clinicId);
    }

    // ------------------------------------------------------------------ helpers

    private static int count(List<ClinicPlan> all, Status s) {
        return (int) all.stream().filter(c -> c.plan().status() == s).count();
    }

    private List<ClinicPlan> plans(String where, Map<String, Object> params) {
        record Row(long id, String name, String city, LocalDate startedOn, LocalDate paidUntil, int staff, int payments,
                   String ownerName, String ownerPhone, String ownerEmail) {
        }
        var q = jdbc.sql("""
                SELECT c.id, c.name, c.city, c.billing_started_on AS started_on, c.paid_until,
                       (SELECT count(DISTINCT m.staff_id) FROM clinic_member m JOIN staff_user s ON s.id = m.staff_id
                         WHERE m.clinic_id = c.id AND s.status = 'ACTIVE' AND s.deleted_at IS NULL)::int AS staff,
                       (SELECT count(*) FROM subscription_payment p WHERE p.clinic_id = c.id)::int AS payments,
                       o.name AS owner_name, o.phone_e164 AS owner_phone, o.email AS owner_email
                  FROM clinic c
                  LEFT JOIN staff_user o ON o.id = (SELECT min(m.staff_id) FROM clinic_member m
                                                      WHERE m.clinic_id = c.id AND m.role = 'ADMIN')
                 WHERE c.deleted_at IS NULL AND %s
                 ORDER BY c.id
                """.formatted(where));
        for (var e : params.entrySet()) {
            q = q.param(e.getKey(), e.getValue());
        }
        LocalDate today = today();
        return q.query(Row.class).list().stream()
                .map(r -> new ClinicPlan(build(r.id(), r.name(), r.startedOn(), r.paidUntil(), r.staff(), today), r.city(),
                        r.ownerName(), r.ownerPhone() == null ? null : r.ownerPhone().replaceFirst("^\\+91", ""),
                        r.ownerEmail(), r.payments()))
                .toList();
    }

    /** The first staff member (the owner) is included; everyone else active is charged. */
    static Plan build(long id, String name, LocalDate start, LocalDate paidUntil, int activeStaff, LocalDate today) {
        int billable = Math.max(0, activeStaff - 1);
        int month = SubscriptionPlan.monthNumber(start, today);
        Stage stage = SubscriptionPlan.stage(month);
        int base = SubscriptionPlan.basePrice(stage);
        int staffCharge = SubscriptionPlan.staffCharge(stage, billable);
        LocalDate free = SubscriptionPlan.freeUntil(start);
        LocalDate covered = paidUntil != null && paidUntil.isAfter(free) ? paidUntil : free;
        Status status = !today.isBefore(covered) ? Status.DUE : today.isBefore(free) ? Status.FREE_MONTH : Status.PAID;
        // What continuing costs: the price of the month that starts when the paid-for time ends (or today if behind)
        LocalDate next = covered.isBefore(today) ? today : covered;
        Stage nextStage = SubscriptionPlan.stage(SubscriptionPlan.monthNumber(start, next));
        int nextAmount = SubscriptionPlan.basePrice(nextStage) + SubscriptionPlan.staffCharge(nextStage, billable);
        LocalDate standard = SubscriptionPlan.standardFrom(start);
        PriceChange change = today.isBefore(free) ? new PriceChange(free, SubscriptionPlan.LAUNCH_PRICE, days(today, free))
                : today.isBefore(standard) ? new PriceChange(standard, SubscriptionPlan.STANDARD_PRICE, days(today, standard)) : null;
        return new Plan(id, name, start, month, stage, base, billable, staffCharge, base + staffCharge, free, paidUntil, covered,
                status, (int) ChronoUnit.DAYS.between(today, covered), nextAmount, change);
    }

    private static int days(LocalDate from, LocalDate to) {
        return (int) ChronoUnit.DAYS.between(from, to);
    }

    private static LocalDate today() {
        return LocalDate.now(IST);
    }
}
