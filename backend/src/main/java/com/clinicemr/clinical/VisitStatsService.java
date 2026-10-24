package com.clinicemr.clinical;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.clinicemr.patient.PatientDtos;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * How many patients the clinic saw today, yesterday, in the last 7 days, this year and in total, and who they were.
 * Days follow India time. A visit counts once it is started (draft or finished); cancelled visits don't count.
 */
@Service
public class VisitStatsService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int LIST_LIMIT = 500;

    public enum Range { TODAY, YESTERDAY, LAST_7_DAYS, THIS_YEAR, ALL }

    /** {@code patients} counts each person once; {@code visits} counts every visit. */
    public record Count(Range range, int patients, int visits) {
    }

    public record Visitor(UUID encounterId, UUID patientId, String name, String mrn, String gender, Integer ageYears,
                          String phone, Instant startedAt, String status, String doctorName, String diagnoses,
                          int visitCount) {
    }

    public record VisitorList(Range range, int patients, int visits, boolean truncated, List<Visitor> items) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public VisitStatsService(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    VisitStatsService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<Count> counts(ClinicContext ctx) {
        return List.of(Range.values()).stream().map(r -> count(ctx, r)).toList();
    }

    private Count count(ClinicContext ctx, Range r) {
        Instant[] w = window(r);
        record Row(int patients, int visits) {
        }
        Row row = jdbc.sql("""
                SELECT count(DISTINCT e.profile_id)::int AS patients, count(*)::int AS visits
                  FROM encounter e
                 WHERE e.clinic_id = :clinicId AND e.status <> 'CANCELLED'
                   AND e.started_at >= :from AND e.started_at < :to
                """)
                .param("clinicId", ctx.clinicId())
                .param("from", java.sql.Timestamp.from(w[0]))
                .param("to", java.sql.Timestamp.from(w[1]))
                .query(Row.class)
                .single();
        return new Count(r, row.patients(), row.visits());
    }

    public VisitorList visitors(ClinicContext ctx, String rangeParam) {
        Range range = parse(rangeParam);
        Instant[] w = window(range);
        // Diagnoses are clinical detail: front desk sees who came, not why
        boolean clinical = ctx.has(ClinicRole.DOCTOR) || ctx.has(ClinicRole.NURSE);
        record Row(UUID encounterId, UUID publicId, String name, String mrn, String gender, LocalDate dob,
                   String primaryPhoneE164, Instant startedAt, String status, String doctorName, String diagnoses,
                   int visitCount) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT e.id AS encounter_id, p.public_id, p.name, pi.mrn, p.gender, p.dob, p.primary_phone_e164,
                       e.started_at, e.status, s.name AS doctor_name,
                       (SELECT count(*) FROM encounter x WHERE x.profile_id = e.profile_id AND x.clinic_id = e.clinic_id
                           AND x.status = 'SIGNED')::int AS visit_count,
                       CASE WHEN :clinical THEN
                         coalesce(CASE WHEN e.status = 'SIGNED'
                                       THEN (SELECT string_agg(d.display, '; ' ORDER BY d.line_no)
                                               FROM diagnosis d WHERE d.encounter_id = e.id)
                                       ELSE (SELECT string_agg(x->>'display', '; ')
                                               FROM jsonb_array_elements(coalesce(e.draft->'diagnoses', '[]'::jsonb)) x)
                                  END, '')
                       END AS diagnoses
                  FROM encounter e
                  JOIN patient_profile p ON p.id = e.profile_id
                  LEFT JOIN patient_identifier pi ON pi.profile_id = p.id AND pi.clinic_id = e.clinic_id
                  JOIN staff_user s ON s.id = e.doctor_id
                 WHERE e.clinic_id = :clinicId AND e.status <> 'CANCELLED'
                   AND e.started_at >= :from AND e.started_at < :to
                 ORDER BY e.started_at DESC
                 LIMIT :limit
                """)
                .param("clinicId", ctx.clinicId())
                .param("clinical", clinical)
                .param("from", java.sql.Timestamp.from(w[0]))
                .param("to", java.sql.Timestamp.from(w[1]))
                .param("limit", LIST_LIMIT + 1)
                .query(Row.class)
                .list();
        boolean truncated = rows.size() > LIST_LIMIT;
        List<Row> shown = truncated ? rows.subList(0, LIST_LIMIT) : rows;
        Count total = count(ctx, range);
        return new VisitorList(range, total.patients(), total.visits(), truncated, shown.stream()
                .map(r -> new Visitor(r.encounterId(), r.publicId(), r.name(), r.mrn(), r.gender(), PatientDtos.age(r.dob()),
                        r.primaryPhoneE164(), r.startedAt(), r.status(), r.doctorName(), r.diagnoses(), r.visitCount()))
                .toList());
    }

    /** [from, to) in UTC instants for a range of India-time calendar days. */
    Instant[] window(Range range) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        Instant end = today.plusDays(1).atStartOfDay(IST).toInstant();
        return switch (range) {
            case TODAY -> new Instant[] { today.atStartOfDay(IST).toInstant(), end };
            case YESTERDAY -> new Instant[] { today.minusDays(1).atStartOfDay(IST).toInstant(), today.atStartOfDay(IST).toInstant() };
            case LAST_7_DAYS -> new Instant[] { today.minusDays(6).atStartOfDay(IST).toInstant(), end };
            case THIS_YEAR -> new Instant[] { today.withDayOfYear(1).atStartOfDay(IST).toInstant(), end };
            case ALL -> new Instant[] { Instant.parse("2000-01-01T00:00:00Z"), end };
        };
    }

    private static Range parse(String value) {
        try {
            return Range.valueOf((value == null ? "TODAY" : value).trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("BAD_RANGE", "Range must be one of today, yesterday, last_7_days, this_year, all.");
        }
    }
}
