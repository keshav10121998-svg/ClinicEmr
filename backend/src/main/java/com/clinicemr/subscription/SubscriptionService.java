package com.clinicemr.subscription;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.AdminContext;
import com.clinicemr.shared.security.ClinicAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which clinics can use the app. The terms are agreed with each clinic in person; the super admin then switches the
 * clinic on for a number of months, free or at the agreed monthly price, and records the money received. A clinic
 * whose access has ended (or that was never switched on) can still read its records, but cannot add or change
 * anything: that rule is applied to every clinic request in {@code ClinicContextResolver}.
 */
@Service
public class SubscriptionService {

    private static final int MAX_AMOUNT = 100_000;
    private static final int EXPIRING_DAYS = 7;

    public enum Status { ACTIVE, EXPIRING, EXPIRED, NOT_ENABLED }

    /**
     * {@code accessUntil} is the last day the clinic can make changes. {@code monthlyPrice} (0 is free) is only given
     * to clinic admins and the super admin.
     */
    public record Plan(long clinicId, String clinicName, Status status, boolean blocked, LocalDate accessUntil,
                       Integer daysLeft, Integer monthlyPrice) {
    }

    /** A clinic as the super admin sees it, with who to contact. */
    public record ClinicPlan(Plan plan, String city, String ownerName, String ownerPhone, String ownerEmail, int payments) {
    }

    /** {@code monthlyIncome}: the agreed monthly prices of clinics that are switched on. */
    public record Overview(int clinics, int active, int expiring, int expired, int notEnabled, int monthlyIncome,
                           List<ClinicPlan> items) {
    }

    public record Payment(long id, int amount, int months, int monthlyPrice, LocalDate coversFrom, LocalDate coversUntil,
                          String note, Instant recordedAt) {
    }

    /** {@code free}: no money for these months. Otherwise {@code monthlyPrice} is the price agreed with the clinic. */
    public record Grant(Integer months, boolean free, Integer monthlyPrice, Integer amount, String note) {
    }

    private final JdbcClient jdbc;
    private final AuditService audit;

    public SubscriptionService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** One clinic's access, for its own staff (the price only when {@code withPrice}). */
    public Plan plan(long clinicId, boolean withPrice) {
        Plan p = plans("c.id = :id", Map.of("id", clinicId)).stream().findFirst()
                .map(ClinicPlan::plan).orElseThrow(() -> ApiException.notFound("Clinic"));
        return withPrice ? p : new Plan(p.clinicId(), p.clinicName(), p.status(), p.blocked(), p.accessUntil(), p.daysLeft(), null);
    }

    /** Every clinic: those that need attention first (not switched on, then ended), then by the day access ends. */
    public Overview overview() {
        List<ClinicPlan> all = plans("TRUE", Map.of()).stream()
                .sorted(Comparator.comparingInt((ClinicPlan c) -> order(c.plan().status()))
                        .thenComparing(c -> c.plan().accessUntil() == null ? LocalDate.MIN : c.plan().accessUntil())
                        .thenComparing(c -> c.plan().clinicName()))
                .toList();
        int income = all.stream().filter(c -> !c.plan().blocked()).mapToInt(c -> c.plan().monthlyPrice()).sum();
        return new Overview(all.size(), count(all, Status.ACTIVE), count(all, Status.EXPIRING), count(all, Status.EXPIRED),
                count(all, Status.NOT_ENABLED), income, all);
    }

    public List<Payment> payments(long clinicId) {
        return jdbc.sql("""
                SELECT id, amount, months, monthly_price, covers_from, covers_until, note, recorded_at
                  FROM subscription_payment WHERE clinic_id = :id ORDER BY recorded_at DESC, id DESC LIMIT 100
                """)
                .param("id", clinicId)
                .query(Payment.class)
                .list();
    }

    /**
     * Switches the clinic on for {@code months} more months. A clinic that is still on gets them after its current end
     * date; one that ended or was never switched on gets them from today. The price agreed is kept on the clinic.
     */
    @Transactional
    public Plan grant(AdminContext admin, long clinicId, Grant req) {
        if (req.months() == null || req.months() < 1 || req.months() > 36) {
            throw ApiException.badRequest("INVALID_MONTHS", "Choose how many months to switch the clinic on for (1 to 36).");
        }
        int price = req.free() ? 0 : req.monthlyPrice() == null ? -1 : req.monthlyPrice();
        if (price < 0 || price > MAX_AMOUNT || (!req.free() && price == 0)) {
            throw ApiException.badRequest("INVALID_PRICE", "Enter the monthly price agreed with the clinic, in rupees, or choose Free.");
        }
        int amount = req.free() ? 0 : req.amount() == null ? price * req.months() : req.amount();
        if (amount < 0 || amount > MAX_AMOUNT * 12) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter the amount received, in rupees (0 if nothing yet).");
        }
        Plan before = plan(clinicId, true);
        LocalDate today = ClinicAccess.today();
        LocalDate from = before.blocked() ? today : before.accessUntil();
        LocalDate until = from.plusMonths(req.months());
        String text = req.note() == null || req.note().isBlank() ? null : req.note().trim().substring(0, Math.min(req.note().trim().length(), 300));
        jdbc.sql("""
                INSERT INTO subscription_payment (clinic_id, amount, months, monthly_price, covers_from, covers_until, note, recorded_by)
                VALUES (:id, :amount, :months, :price, :from, :until, :note, :admin)
                """)
                .param("id", clinicId).param("amount", amount).param("months", req.months()).param("price", price)
                .param("from", from).param("until", until).param("note", text).param("admin", admin.adminId())
                .update();
        jdbc.sql("UPDATE clinic SET paid_until = :until, monthly_price = :price WHERE id = :id")
                .param("until", until).param("price", price).param("id", clinicId).update();
        audit.record("ADMIN", String.valueOf(admin.adminId()), clinicId, "CLINIC_SWITCHED_ON", "clinic", String.valueOf(clinicId),
                Map.of("months", req.months(), "monthlyPrice", price, "amount", amount));
        return plan(clinicId, true);
    }

    /** Ends access today: the clinic becomes read-only until it is switched on again. */
    @Transactional
    public Plan switchOff(AdminContext admin, long clinicId) {
        plan(clinicId, true);
        jdbc.sql("UPDATE clinic SET paid_until = :yesterday WHERE id = :id")
                .param("yesterday", ClinicAccess.today().minusDays(1)).param("id", clinicId).update();
        audit.record("ADMIN", String.valueOf(admin.adminId()), clinicId, "CLINIC_SWITCHED_OFF", "clinic", String.valueOf(clinicId), Map.of());
        return plan(clinicId, true);
    }

    // ------------------------------------------------------------------ helpers

    private static int order(Status s) {
        return switch (s) {
            case NOT_ENABLED -> 0;
            case EXPIRED -> 1;
            case EXPIRING -> 2;
            case ACTIVE -> 3;
        };
    }

    private static int count(List<ClinicPlan> all, Status s) {
        return (int) all.stream().filter(c -> c.plan().status() == s).count();
    }

    private List<ClinicPlan> plans(String where, Map<String, Object> params) {
        record Row(long id, String name, String city, LocalDate paidUntil, int monthlyPrice, int payments,
                   String ownerName, String ownerPhone, String ownerEmail) {
        }
        var q = jdbc.sql("""
                SELECT c.id, c.name, c.city, c.paid_until, c.monthly_price,
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
        LocalDate today = ClinicAccess.today();
        return q.query(Row.class).list().stream()
                .map(r -> new ClinicPlan(build(r.id(), r.name(), r.paidUntil(), r.monthlyPrice(), today), r.city(),
                        r.ownerName(), r.ownerPhone() == null ? null : r.ownerPhone().replaceFirst("^\\+91", ""),
                        r.ownerEmail(), r.payments()))
                .toList();
    }

    static Plan build(long id, String name, LocalDate accessUntil, int monthlyPrice, LocalDate today) {
        boolean active = ClinicAccess.active(accessUntil, today);
        int daysLeft = accessUntil == null ? 0 : (int) ChronoUnit.DAYS.between(today, accessUntil);
        Status status = accessUntil == null ? Status.NOT_ENABLED : !active ? Status.EXPIRED
                : daysLeft <= EXPIRING_DAYS ? Status.EXPIRING : Status.ACTIVE;
        return new Plan(id, name, status, !active, accessUntil, accessUntil == null ? null : daysLeft, monthlyPrice);
    }
}
