package com.clinicemr.platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.AdminContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the super admin sees across every clinic, and the clean-up of test data. Deleting never erases medical
 * records: removed doctors, clinics, patients and visits keep their rows (with when and why) and the app treats them
 * as gone. A removed doctor's email and mobile number are freed so they can register again.
 */
@Service
public class PlatformAdminService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public PlatformAdminService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Overview(int clinics, int doctors, int staff, int patients, int appUsers, int visits, int visitsToday,
                           int visitsLast7Days, int newClinicsLast7Days, int newPatientsLast7Days,
                           int deletedDoctors, int deletedVisits) {
    }

    // Visits on a clinic's practice patient never count
    private static final String REAL = "NOT EXISTS (SELECT 1 FROM patient_profile sp WHERE sp.id = e.profile_id AND sp.sample)";

    private static final String IST_TODAY = "(date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata')";

    public Overview overview() {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM clinic WHERE deleted_at IS NULL)::int AS clinics,
                       (SELECT count(*) FROM staff_user s JOIN doctor d ON d.staff_id = s.id WHERE s.deleted_at IS NULL)::int AS doctors,
                       (SELECT count(*) FROM staff_user WHERE deleted_at IS NULL)::int AS staff,
                       (SELECT count(*) FROM patient_profile
                         WHERE deleted_at IS NULL AND merged_into_id IS NULL AND NOT sample)::int AS patients,
                       (SELECT count(*) FROM account WHERE deleted_at IS NULL)::int AS app_users,
                       (SELECT count(*) FROM encounter e WHERE e.status = 'SIGNED' AND e.deleted_at IS NULL AND %2$s)::int AS visits,
                       (SELECT count(*) FROM encounter e WHERE e.status <> 'CANCELLED' AND e.started_at >= %1$s AND %2$s)::int AS visits_today,
                       (SELECT count(*) FROM encounter e
                         WHERE e.status <> 'CANCELLED' AND e.started_at >= %1$s - interval '6 days' AND %2$s)::int AS visits_last7_days,
                       (SELECT count(*) FROM clinic WHERE deleted_at IS NULL AND created_at >= now() - interval '7 days')::int AS new_clinics_last7_days,
                       (SELECT count(*) FROM patient_profile
                         WHERE deleted_at IS NULL AND NOT sample AND created_at >= now() - interval '7 days')::int AS new_patients_last7_days,
                       (SELECT count(*) FROM staff_user WHERE deleted_at IS NOT NULL)::int AS deleted_doctors,
                       (SELECT count(*) FROM encounter WHERE deleted_at IS NOT NULL)::int AS deleted_visits
                """.formatted(IST_TODAY, REAL))
                .query(Overview.class)
                .single();
    }

    public record Doctor(long id, String name, String email, String phone, String status, Instant createdAt,
                         Instant deletedAt, boolean isDoctor, String registrationNo, String qualifications,
                         String specialty, String clinics, int visits, int patients, Instant lastVisitAt) {
    }

    private static final String DOCTOR_SELECT = """
            SELECT s.id, s.name, coalesce(s.deleted_email, s.email) AS email, s.phone_e164 AS phone, s.status,
                   s.created_at, s.deleted_at, d.staff_id IS NOT NULL AS is_doctor,
                   d.registration_no, d.qualifications, d.specialty,
                   coalesce((SELECT string_agg(DISTINCT c.name, ', ') FROM clinic_member m JOIN clinic c ON c.id = m.clinic_id
                              WHERE m.staff_id = s.id), '') AS clinics,
                   (SELECT count(*) FROM encounter e
                     WHERE e.doctor_id = s.id AND e.status = 'SIGNED' AND e.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM patient_profile sp WHERE sp.id = e.profile_id AND sp.sample))::int AS visits,
                   (SELECT count(DISTINCT pi.profile_id) FROM patient_identifier pi
                      JOIN patient_profile p ON p.id = pi.profile_id
                      JOIN clinic_member m ON m.clinic_id = pi.clinic_id
                     WHERE m.staff_id = s.id AND p.deleted_at IS NULL AND NOT p.sample)::int AS patients,
                   (SELECT max(e.started_at) FROM encounter e
                     WHERE e.doctor_id = s.id AND e.status <> 'CANCELLED' AND NOT EXISTS (SELECT 1 FROM patient_profile sp WHERE sp.id = e.profile_id AND sp.sample)) AS last_visit_at
              FROM staff_user s LEFT JOIN doctor d ON d.staff_id = s.id
            """;

    /** Everyone who signs in to a clinic (doctors first), newest first; removed ones only when asked. */
    public List<Doctor> doctors(boolean includeDeleted) {
        return jdbc.sql(DOCTOR_SELECT + """
                 WHERE (:deleted OR s.deleted_at IS NULL)
                 ORDER BY s.deleted_at IS NOT NULL, d.staff_id IS NULL, s.created_at DESC
                 LIMIT 1000
                """)
                .param("deleted", includeDeleted)
                .query(Doctor.class)
                .list();
    }

    public record ClinicInfo(long id, String name, String city, String status, Instant createdAt, Instant deletedAt,
                             String roles, int patients, int staff) {
    }

    public record Visit(UUID id, Instant startedAt, String status, Instant deletedAt, String deletedReason,
                        String patientName, String mrn, String clinicName, String diagnoses) {
    }

    public record DoctorDetail(Doctor doctor, List<ClinicInfo> clinics, List<Visit> visits, int draftVisits) {
    }

    public DoctorDetail doctor(long staffId) {
        Doctor d = jdbc.sql(DOCTOR_SELECT + " WHERE s.id = :id")
                .param("id", staffId)
                .query(Doctor.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Doctor"));
        List<ClinicInfo> clinics = jdbc.sql("""
                SELECT c.id, c.name, c.city, c.status, c.created_at, c.deleted_at,
                       string_agg(m.role, ', ' ORDER BY m.role) AS roles,
                       (SELECT count(*) FROM patient_identifier pi JOIN patient_profile p ON p.id = pi.profile_id
                         WHERE pi.clinic_id = c.id AND p.deleted_at IS NULL AND NOT p.sample)::int AS patients,
                       (SELECT count(DISTINCT o.staff_id) FROM clinic_member o JOIN staff_user os ON os.id = o.staff_id
                         WHERE o.clinic_id = c.id AND os.deleted_at IS NULL)::int AS staff
                  FROM clinic_member m JOIN clinic c ON c.id = m.clinic_id
                 WHERE m.staff_id = :id
                 GROUP BY c.id
                 ORDER BY c.name
                """)
                .param("id", staffId)
                .query(ClinicInfo.class)
                .list();
        List<Visit> visits = jdbc.sql("""
                SELECT e.id, e.started_at, e.status, e.deleted_at, e.deleted_reason, p.name AS patient_name, pi.mrn,
                       c.name AS clinic_name,
                       coalesce(CASE WHEN e.status = 'SIGNED'
                                     THEN (SELECT string_agg(x.display, '; ' ORDER BY x.line_no) FROM diagnosis x WHERE x.encounter_id = e.id)
                                     ELSE (SELECT string_agg(x->>'display', '; ')
                                             FROM jsonb_array_elements(coalesce(e.draft->'diagnoses', '[]'::jsonb)) x)
                                END, '') AS diagnoses
                  FROM encounter e
                  JOIN patient_profile p ON p.id = e.profile_id
                  JOIN clinic c ON c.id = e.clinic_id
                  LEFT JOIN patient_identifier pi ON pi.profile_id = p.id AND pi.clinic_id = e.clinic_id
                 WHERE e.doctor_id = :id AND (e.status <> 'CANCELLED' OR e.deleted_at IS NOT NULL) AND NOT p.sample
                 ORDER BY e.started_at DESC
                 LIMIT 300
                """)
                .param("id", staffId)
                .query(Visit.class)
                .list();
        int drafts = (int) visits.stream().filter(v -> "DRAFT".equals(v.status())).count();
        return new DoctorDetail(d, clinics, visits, drafts);
    }

    /** Hides one visit everywhere (clinic, patient app, counts, payments due). The reason is kept with it. */
    @Transactional
    public void deleteVisit(AdminContext admin, UUID visitId, String reason) {
        String why = reason == null || reason.isBlank() ? "Removed by super admin" : reason.trim();
        if (why.length() > 300) {
            throw ApiException.badRequest("REASON_TOO_LONG", "Keep the reason under 300 characters.");
        }
        Long clinicId = jdbc.sql("""
                UPDATE encounter SET status = 'CANCELLED', deleted_at = now(), deleted_reason = :reason, updated_at = now()
                 WHERE id = :id AND deleted_at IS NULL
                RETURNING clinic_id
                """)
                .param("id", visitId)
                .param("reason", why)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Visit"));
        audit.record("ADMIN", String.valueOf(admin.adminId()), clinicId, "VISIT_DELETED", "encounter", visitId.toString(),
                Map.of("reason", why));
    }

    public record DeleteResult(int visitsDeleted, List<String> clinicsDeleted, int patientsDeleted) {
    }

    /**
     * Removes a doctor (or any staff account): they can't sign in, and their email and mobile number are freed. With
     * {@code alsoVisits} their visits are removed too. A clinic left with nobody in it is removed with its visits and
     * the patients registered only there.
     */
    @Transactional
    public DeleteResult deleteDoctor(AdminContext admin, long staffId, boolean alsoVisits) {
        int n = jdbc.sql("""
                UPDATE staff_user
                   SET status = 'DISABLED', deleted_at = now(), deleted_email = email,
                       email = 'deleted-' || id || '@removed.invalid', phone_e164 = NULL
                 WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", staffId)
                .update();
        if (n == 0) {
            throw ApiException.notFound("Doctor");
        }
        int visits = alsoVisits ? jdbc.sql("""
                UPDATE encounter SET status = 'CANCELLED', deleted_at = now(), deleted_reason = 'Doctor removed', updated_at = now()
                 WHERE doctor_id = :id AND deleted_at IS NULL
                """).param("id", staffId).update() : 0;

        // Clinics nobody is left in
        record Emptied(long id, String name) {
        }
        List<Emptied> emptied = jdbc.sql("""
                SELECT DISTINCT c.id, c.name FROM clinic_member m JOIN clinic c ON c.id = m.clinic_id
                 WHERE m.staff_id = :id AND c.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM clinic_member o JOIN staff_user os ON os.id = o.staff_id
                                    WHERE o.clinic_id = c.id AND os.deleted_at IS NULL)
                """)
                .param("id", staffId)
                .query(Emptied.class)
                .list();
        int patients = 0;
        if (!emptied.isEmpty()) {
            List<Long> ids = emptied.stream().map(Emptied::id).toList();
            jdbc.sql("UPDATE clinic SET status = 'SUSPENDED', deleted_at = now() WHERE id IN (:ids)").param("ids", ids).update();
            visits += jdbc.sql("""
                    UPDATE encounter SET status = 'CANCELLED', deleted_at = now(), deleted_reason = 'Clinic removed', updated_at = now()
                     WHERE clinic_id IN (:ids) AND deleted_at IS NULL
                    """).param("ids", ids).update();
            // Patients registered only at removed clinics; anyone also seen at a clinic still in use stays
            patients = jdbc.sql("""
                    UPDATE patient_profile p SET deleted_at = now()
                     WHERE p.deleted_at IS NULL
                       AND EXISTS (SELECT 1 FROM patient_identifier pi WHERE pi.profile_id = p.id AND pi.clinic_id IN (:ids))
                       AND NOT EXISTS (SELECT 1 FROM patient_identifier pi JOIN clinic c ON c.id = pi.clinic_id
                                        WHERE pi.profile_id = p.id AND c.deleted_at IS NULL)
                    """).param("ids", ids).update();
        }
        DeleteResult result = new DeleteResult(visits, emptied.stream().map(Emptied::name).toList(), patients);
        audit.record("ADMIN", String.valueOf(admin.adminId()), null, "DOCTOR_DELETED", "staff_user", String.valueOf(staffId),
                Map.of("visits", visits, "clinics", result.clinicsDeleted(), "patients", patients));
        return result;
    }
}
