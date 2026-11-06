package com.clinicemr.billing;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.clinicemr.patient.PatientDtos;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Earnings for today, this month, this year and all time (India-time calendar days). For the visits in each period:
 * the total charged, what was paid and the balance still owed. {@code collected} is the money actually received in
 * the period, including old balances paid now. Doctors see their own visits; admins and front desk can also see the
 * whole clinic. Cancelled visits, the sample patient and removed patients don't count.
 */
@Service
public class EarningsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int LIST_LIMIT = 500;

    public enum Range { TODAY, THIS_MONTH, THIS_YEAR, ALL }

    public record Period(Range range, int visits, int total, int paid, int balance, int collected) {
    }

    /** {@code mine}: the numbers are the signed-in doctor's own; {@code canSeeClinic}: they may switch to the clinic's. */
    public record Earnings(boolean mine, boolean canSeeClinic, List<Period> periods) {
    }

    /** One patient who still owes money for visits in the period. {@code overdueDays} as in "Money to collect". */
    public record Owing(UUID patientId, String name, String mrn, String gender, Integer ageYears, String phone,
                        int balance, int visits, LocalDate dueDate, int overdueDays, Instant lastVisitAt) {
    }

    public record OwingList(Range range, boolean mine, int patients, int balance, boolean truncated, List<Owing> items) {
    }

    private final JdbcClient jdbc;

    public EarningsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Earnings earnings(ClinicContext ctx, boolean mineAsked) {
        boolean mine = scope(ctx, mineAsked);
        return new Earnings(mine, canSeeClinic(ctx), periods(ctx, mine));
    }

    /**
     * All four periods in one database round trip (the database is far from the app server, so each trip costs
     * time): each period sums the same visits and payments from its own start date.
     */
    private List<Period> periods(ClinicContext ctx, boolean mine) {
        return jdbc.sql("""
                WITH billed AS (
                  SELECT e.started_at, vp.paid, vp.balance
                    FROM visit_payment vp
                    JOIN encounter e ON e.id = vp.encounter_id
                    JOIN patient_profile p ON p.id = vp.profile_id
                   WHERE vp.clinic_id = :clinicId AND e.status <> 'CANCELLED' AND NOT p.sample AND p.deleted_at IS NULL
                     AND (NOT :mine OR e.doctor_id = :staffId) AND e.started_at < :end
                ), received AS (
                  SELECT pe.received_at, pe.amount
                    FROM payment_entry pe
                    JOIN encounter e ON e.id = pe.encounter_id
                    JOIN patient_profile p ON p.id = pe.profile_id
                   WHERE pe.clinic_id = :clinicId AND e.status <> 'CANCELLED' AND NOT p.sample AND p.deleted_at IS NULL
                     AND (NOT :mine OR e.doctor_id = :staffId) AND pe.received_at < :end
                )
                SELECT r.period,
                       (SELECT count(*) FROM billed b WHERE b.started_at >= r.since)::int AS visits,
                       (SELECT coalesce(sum(b.paid), 0) FROM billed b WHERE b.started_at >= r.since)::int AS paid,
                       (SELECT coalesce(sum(b.balance), 0) FROM billed b WHERE b.started_at >= r.since)::int AS balance,
                       (SELECT coalesce(sum(x.amount), 0) FROM received x WHERE x.received_at >= r.since)::int AS collected
                  FROM (VALUES (1, 'TODAY', CAST(:today AS timestamptz)), (2, 'THIS_MONTH', CAST(:month AS timestamptz)),
                               (3, 'THIS_YEAR', CAST(:year AS timestamptz)), (4, 'ALL', CAST(:all AS timestamptz)))
                       AS r(n, period, since)
                 ORDER BY r.n
                """)
                .param("clinicId", ctx.clinicId())
                .param("mine", mine)
                .param("staffId", ctx.staffId())
                .param("end", Timestamp.from(window(Range.TODAY)[1]))
                .param("today", Timestamp.from(window(Range.TODAY)[0]))
                .param("month", Timestamp.from(window(Range.THIS_MONTH)[0]))
                .param("year", Timestamp.from(window(Range.THIS_YEAR)[0]))
                .param("all", Timestamp.from(window(Range.ALL)[0]))
                .query((rs, i) -> new Period(Range.valueOf(rs.getString("period")), rs.getInt("visits"),
                        rs.getInt("paid") + rs.getInt("balance"), rs.getInt("paid"), rs.getInt("balance"), rs.getInt("collected")))
                .list();
    }

    /** Patients with a balance on visits in the period, the longest overdue first. */
    public OwingList owing(ClinicContext ctx, String rangeParam, boolean mineAsked) {
        Range range = parse(rangeParam);
        boolean mine = scope(ctx, mineAsked);
        Instant[] w = window(range);
        record Row(UUID publicId, String name, String mrn, String gender, LocalDate dob, String primaryPhoneE164,
                   int balance, int visits, LocalDate dueDate, Instant lastVisitAt) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT p.public_id, p.name, pi.mrn, p.gender, p.dob, p.primary_phone_e164,
                       sum(vp.balance)::int AS balance, count(*)::int AS visits, min(vp.due_date) AS due_date,
                       max(e.started_at) AS last_visit_at
                  FROM visit_payment vp
                  JOIN encounter e ON e.id = vp.encounter_id
                  JOIN patient_profile p ON p.id = vp.profile_id
                  LEFT JOIN patient_identifier pi ON pi.profile_id = p.id AND pi.clinic_id = vp.clinic_id
                 WHERE vp.clinic_id = :clinicId AND vp.balance > 0 AND e.status <> 'CANCELLED' AND NOT p.sample
                   AND p.deleted_at IS NULL
                   AND (NOT :mine OR e.doctor_id = :staffId)
                   AND e.started_at >= :from AND e.started_at < :to
                 GROUP BY p.id, pi.mrn
                 ORDER BY min(vp.due_date), sum(vp.balance) DESC, p.name
                 LIMIT :limit
                """)
                .param("clinicId", ctx.clinicId())
                .param("mine", mine)
                .param("staffId", ctx.staffId())
                .param("from", Timestamp.from(w[0]))
                .param("to", Timestamp.from(w[1]))
                .param("limit", LIST_LIMIT + 1)
                .query(Row.class)
                .list();
        boolean truncated = rows.size() > LIST_LIMIT;
        List<Row> shown = truncated ? rows.subList(0, LIST_LIMIT) : rows;
        LocalDate today = LocalDate.now(IST);
        Period p = periods(ctx, mine).stream().filter(x -> x.range() == range).findFirst().orElseThrow();
        return new OwingList(range, mine, shown.size(), p.balance(), truncated, shown.stream()
                .map(r -> new Owing(r.publicId(), r.name(), r.mrn(), r.gender(), PatientDtos.age(r.dob()),
                        r.primaryPhoneE164() == null ? null : r.primaryPhoneE164().substring(3), r.balance(), r.visits(),
                        r.dueDate(), (int) ChronoUnit.DAYS.between(r.dueDate(), today), r.lastVisitAt()))
                .toList());
    }

    /** Doctors who aren't also admin or front desk only ever see their own visits. */
    private static boolean scope(ClinicContext ctx, boolean mineAsked) {
        ctx.require(ClinicRole.ADMIN, ClinicRole.DOCTOR, ClinicRole.FRONT_DESK);
        return mineAsked || !canSeeClinic(ctx);
    }

    private static boolean canSeeClinic(ClinicContext ctx) {
        return ctx.has(ClinicRole.ADMIN) || ctx.has(ClinicRole.FRONT_DESK);
    }

    /** [from, to) in UTC instants for a range of India-time calendar days. */
    private static Instant[] window(Range range) {
        LocalDate today = LocalDate.now(IST);
        Instant end = today.plusDays(1).atStartOfDay(IST).toInstant();
        LocalDate start = switch (range) {
            case TODAY -> today;
            case THIS_MONTH -> today.withDayOfMonth(1);
            case THIS_YEAR -> today.withDayOfYear(1);
            case ALL -> LocalDate.of(2000, 1, 1);
        };
        return new Instant[] { start.atStartOfDay(IST).toInstant(), end };
    }

    private static Range parse(String value) {
        try {
            return Range.valueOf((value == null ? "ALL" : value).trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("BAD_RANGE", "Range must be one of today, this_month, this_year, all.");
        }
    }
}
