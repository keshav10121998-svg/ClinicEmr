package com.clinicemr.rating;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.clinicemr.patientapp.PatientRecordsService;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import com.clinicemr.shared.security.PatientContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two kinds of rating. Patients rate a signed visit from the patient app (one per visit, changeable), which rates the
 * clinic and its doctor; the clinic sees the ratings without the patient's name. Clinics rate ClinicEMR itself (one per
 * clinic, changeable) and may allow the feedback to be shown publicly; the super admin sees all of it and the public
 * endpoint shows only what clinics allowed.
 */
@Service
public class RatingService {

    private static final int MAX_COMMENT = 500;
    private static final int RECENT = 50;
    private static final int TESTIMONIALS = 12;

    /** The patient's own rating of a visit. */
    public record MyRating(int stars, String comment) {
    }

    /** {@code distribution[0]} is the number of 1-star ratings, up to {@code distribution[4]} for 5 stars. */
    public record Summary(double average, int count, int[] distribution) {
    }

    public record DoctorRating(long doctorId, String doctorName, double average, int count) {
    }

    /** What a patient wrote, without who they are. */
    public record Comment(int stars, String comment, String doctorName, Instant visitDate, Instant ratedAt) {
    }

    public record ClinicRatings(boolean mine, Summary summary, List<DoctorRating> doctors, List<Comment> recent) {
    }

    public record AppRating(int stars, String comment, boolean allowPublic, Instant updatedAt) {
    }

    public record AppFeedback(long clinicId, String clinicName, String city, int stars, String comment, boolean allowPublic,
                              Instant ratedAt) {
    }

    public record ClinicAverage(long clinicId, String clinicName, double average, int count) {
    }

    /** For the super admin: how clinics rate ClinicEMR, and how patients rate each clinic. */
    public record AdminRatings(Summary app, List<AppFeedback> appFeedback, Summary patients, List<ClinicAverage> clinics) {
    }

    public record Testimonial(int stars, String comment, String clinicName, String city) {
    }

    /** What anyone may see: the average over every clinic's rating, and the words clinics allowed to be shown. */
    public record PublicRating(double average, int count, List<Testimonial> testimonials) {
    }

    private final JdbcClient jdbc;
    private final PatientRecordsService records;
    private final AuditService audit;

    public RatingService(JdbcClient jdbc, PatientRecordsService records, AuditService audit) {
        this.jdbc = jdbc;
        this.records = records;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ patients rate a visit

    public Optional<MyRating> myRating(UUID encounterId) {
        return jdbc.sql("SELECT stars, comment FROM patient_rating WHERE encounter_id = :id")
                .param("id", encounterId).query(MyRating.class).optional();
    }

    @Transactional
    public MyRating rateVisit(PatientContext p, UUID profileId, UUID encounterId, Integer stars, String comment) {
        long profile = records.requireProfile(p, profileId);
        checkStars(stars);
        record Visit(long clinicId, long doctorId) {
        }
        Visit v = jdbc.sql("""
                SELECT clinic_id, doctor_id FROM encounter
                 WHERE id = :id AND profile_id = :profile AND status = 'SIGNED' AND deleted_at IS NULL
                """)
                .param("id", encounterId).param("profile", profile)
                .query(Visit.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Visit was not found."));
        String text = text(comment);
        jdbc.sql("""
                INSERT INTO patient_rating (encounter_id, clinic_id, doctor_id, profile_id, account_id, stars, comment)
                VALUES (:id, :clinic, :doctor, :profile, :account, :stars, :comment)
                ON CONFLICT (encounter_id) DO UPDATE
                   SET stars = :stars, comment = :comment, account_id = :account, updated_at = now()
                """)
                .param("id", encounterId).param("clinic", v.clinicId()).param("doctor", v.doctorId())
                .param("profile", profile).param("account", p.accountId()).param("stars", stars).param("comment", text)
                .update();
        audit.record("PATIENT", "account:" + p.accountId(), v.clinicId(), "VISIT_RATED", "encounter", encounterId.toString(),
                java.util.Map.of("stars", stars));
        return new MyRating(stars, text);
    }

    // ------------------------------------------------------------------ the clinic sees its ratings

    /** Clinic admins see the whole clinic; a doctor who isn't an admin sees only the ratings of their own visits. */
    public ClinicRatings clinicRatings(ClinicContext ctx) {
        ctx.require(ClinicRole.ADMIN, ClinicRole.DOCTOR);
        boolean mine = !ctx.has(ClinicRole.ADMIN);
        String scope = """
                  FROM patient_rating r
                  JOIN encounter e ON e.id = r.encounter_id AND e.deleted_at IS NULL
                  JOIN patient_profile pp ON pp.id = r.profile_id AND pp.deleted_at IS NULL AND NOT pp.sample
                  JOIN staff_user s ON s.id = r.doctor_id
                 WHERE r.clinic_id = :clinic AND (NOT :mine OR r.doctor_id = :staff)
                """;
        Summary summary = summary("SELECT r.stars " + scope, ctx.clinicId(), ctx.staffId(), mine);
        List<DoctorRating> doctors = jdbc.sql("""
                SELECT r.doctor_id, s.name AS doctor_name, round(avg(r.stars), 1)::float8 AS average, count(*)::int AS count
                """ + scope + " GROUP BY r.doctor_id, s.name ORDER BY count(*) DESC, s.name")
                .param("clinic", ctx.clinicId()).param("mine", mine).param("staff", ctx.staffId())
                .query(DoctorRating.class).list();
        List<Comment> recent = jdbc.sql("""
                SELECT r.stars, r.comment, s.name AS doctor_name, e.started_at AS visit_date, r.updated_at AS rated_at
                """ + scope + " ORDER BY r.updated_at DESC LIMIT " + RECENT)
                .param("clinic", ctx.clinicId()).param("mine", mine).param("staff", ctx.staffId())
                .query(Comment.class).list();
        return new ClinicRatings(mine, summary, doctors, recent);
    }

    // ------------------------------------------------------------------ the clinic rates ClinicEMR

    public Optional<AppRating> appRating(ClinicContext ctx) {
        ctx.require(ClinicRole.ADMIN, ClinicRole.DOCTOR);
        return jdbc.sql("SELECT stars, comment, allow_public, updated_at FROM app_rating WHERE clinic_id = :id")
                .param("id", ctx.clinicId()).query(AppRating.class).optional();
    }

    @Transactional
    public AppRating saveAppRating(ClinicContext ctx, Integer stars, String comment, boolean allowPublic) {
        ctx.require(ClinicRole.ADMIN, ClinicRole.DOCTOR);
        checkStars(stars);
        jdbc.sql("""
                INSERT INTO app_rating (clinic_id, staff_id, stars, comment, allow_public)
                VALUES (:clinic, :staff, :stars, :comment, :pub)
                ON CONFLICT (clinic_id) DO UPDATE
                   SET staff_id = :staff, stars = :stars, comment = :comment, allow_public = :pub, updated_at = now()
                """)
                .param("clinic", ctx.clinicId()).param("staff", ctx.staffId()).param("stars", stars)
                .param("comment", text(comment)).param("pub", allowPublic)
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "APP_RATED", "clinic", String.valueOf(ctx.clinicId()),
                java.util.Map.of("stars", stars, "public", allowPublic));
        return appRating(ctx).orElseThrow();
    }

    // ------------------------------------------------------------------ super admin and the public

    public AdminRatings adminRatings() {
        Summary app = jdbc.sql("""
                SELECT stars FROM app_rating a JOIN clinic c ON c.id = a.clinic_id AND c.deleted_at IS NULL
                """).query(Integer.class).list().stream().collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(), RatingService::summaryOf));
        List<AppFeedback> feedback = jdbc.sql("""
                SELECT a.clinic_id, c.name AS clinic_name, c.city, a.stars, a.comment, a.allow_public, a.updated_at AS rated_at
                  FROM app_rating a JOIN clinic c ON c.id = a.clinic_id AND c.deleted_at IS NULL
                 ORDER BY a.updated_at DESC LIMIT 200
                """).query(AppFeedback.class).list();
        String patientScope = """
                  FROM patient_rating r
                  JOIN clinic c ON c.id = r.clinic_id AND c.deleted_at IS NULL
                  JOIN encounter e ON e.id = r.encounter_id AND e.deleted_at IS NULL
                  JOIN patient_profile pp ON pp.id = r.profile_id AND pp.deleted_at IS NULL AND NOT pp.sample
                """;
        Summary patients = summaryOf(jdbc.sql("SELECT r.stars " + patientScope).query(Integer.class).list());
        List<ClinicAverage> clinics = jdbc.sql("""
                SELECT c.id AS clinic_id, c.name AS clinic_name, round(avg(r.stars), 1)::float8 AS average, count(*)::int AS count
                """ + patientScope + " GROUP BY c.id, c.name ORDER BY avg(r.stars) DESC, count(*) DESC")
                .query(ClinicAverage.class).list();
        return new AdminRatings(app, feedback, patients, clinics);
    }

    public PublicRating publicRating() {
        Summary all = summaryOf(jdbc.sql("""
                SELECT stars FROM app_rating a JOIN clinic c ON c.id = a.clinic_id AND c.deleted_at IS NULL
                """).query(Integer.class).list());
        List<Testimonial> words = jdbc.sql("""
                SELECT a.stars, a.comment, c.name AS clinic_name, c.city
                  FROM app_rating a JOIN clinic c ON c.id = a.clinic_id AND c.deleted_at IS NULL
                 WHERE a.allow_public AND a.comment IS NOT NULL
                 ORDER BY a.stars DESC, a.updated_at DESC LIMIT %d
                """.formatted(TESTIMONIALS)).query(Testimonial.class).list();
        return new PublicRating(all.average(), all.count(), words);
    }

    // ------------------------------------------------------------------ helpers

    private Summary summary(String starsSql, long clinicId, long staffId, boolean mine) {
        return summaryOf(jdbc.sql(starsSql).param("clinic", clinicId).param("mine", mine).param("staff", staffId)
                .query(Integer.class).list());
    }

    static Summary summaryOf(List<Integer> stars) {
        int[] distribution = new int[5];
        long sum = 0;
        for (int s : stars) {
            distribution[s - 1]++;
            sum += s;
        }
        double average = stars.isEmpty() ? 0 : Math.round(sum * 10.0 / stars.size()) / 10.0;
        return new Summary(average, stars.size(), distribution);
    }

    private static void checkStars(Integer stars) {
        if (stars == null || stars < 1 || stars > 5) {
            throw ApiException.badRequest("INVALID_STARS", "Choose 1 to 5 stars.");
        }
    }

    /** An optional comment, trimmed to 500 characters; blank means none. */
    private static String text(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        return t.length() > MAX_COMMENT ? t.substring(0, MAX_COMMENT) : t;
    }
}
