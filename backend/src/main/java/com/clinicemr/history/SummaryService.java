package com.clinicemr.history;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.clinicemr.clinical.EncounterContent.RxItem;
import com.clinicemr.history.HistoryDtos.ActiveProblem;
import com.clinicemr.history.HistoryDtos.CurrentMedicine;
import com.clinicemr.history.HistoryDtos.LastPrescription;
import com.clinicemr.history.HistoryDtos.LastVisit;
import com.clinicemr.history.HistoryDtos.PatientSummary;
import com.clinicemr.history.HistoryDtos.VitalPoint;
import com.clinicemr.history.HistoryDtos.VitalSeries;
import com.clinicemr.history.HistoryDtos.VitalValue;
import com.clinicemr.patient.PatientService;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The patient header a doctor sees first: allergies, active problems, current medicines, latest vitals and the
 * last visit. Problems and medicines are computed from signed visits at read time, so amendments show up
 * immediately; doctors can mark a problem resolved or stop a long-term medicine on top of that.
 * <p>
 * Only this clinic's records are used for now. Records from other clinics will be added through
 * patient-approved access grants.
 */
@Service
public class SummaryService {

    /** Key used to group the same problem across visits: its code, or the lower-cased name if uncoded. */
    static final String PROBLEM_KEY = "coalesce(nullif(d.code, ''), lower(d.display))";
    /** Key used to group the same medicine across visits: generic name, or the drug as written. */
    static final String MEDICATION_KEY = "coalesce(lower(nullif(pi.generic_name, '')), lower(pi.drug_display))";

    private final JdbcClient jdbc;
    private final PatientService patients;
    private final AllergyService allergies;
    private final AuditService audit;

    public SummaryService(JdbcClient jdbc, PatientService patients, AllergyService allergies, AuditService audit) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.allergies = allergies;
        this.audit = audit;
    }

    public PatientSummary summary(ClinicContext ctx, UUID patientId) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        return new PatientSummary(
                patients.getByProfileId(ctx, profileId),
                allergies.listFor(profileId, ctx.clinicId()),
                activeProblems(profileId, ctx.clinicId()),
                currentMedicines(profileId, ctx.clinicId()),
                latestVitals(profileId, ctx.clinicId()),
                lastVisit(profileId, ctx.clinicId()).orElse(null));
    }

    List<ActiveProblem> activeProblems(long profileId, long clinicId) {
        return jdbc.sql("""
                WITH dx AS (
                    SELECT %s AS problem_key, d.code, d.display, e.started_at
                      FROM diagnosis d JOIN encounter e ON e.id = d.encounter_id
                     WHERE d.profile_id = :profileId AND e.clinic_id = :clinicId AND e.status = 'SIGNED' AND d.chronic
                )
                SELECT dx.problem_key,
                       (array_agg(dx.code ORDER BY dx.started_at DESC))[1] AS code,
                       (array_agg(dx.display ORDER BY dx.started_at DESC))[1] AS display,
                       min(dx.started_at) AS since,
                       max(dx.started_at) AS last_seen
                  FROM dx
                 GROUP BY dx.problem_key
                HAVING NOT EXISTS (SELECT 1 FROM problem_status ps
                                    WHERE ps.profile_id = :profileId AND ps.clinic_id = :clinicId
                                      AND ps.problem_key = dx.problem_key AND ps.changed_at >= max(dx.started_at))
                 ORDER BY last_seen DESC
                """.formatted(PROBLEM_KEY))
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .query(ActiveProblem.class)
                .list();
    }

    List<CurrentMedicine> currentMedicines(long profileId, long clinicId) {
        // A course of N days started on day D runs through D + N - 1 (dates in IST)
        return jdbc.sql("""
                WITH items AS (
                    SELECT %s AS medication_key, pi.drug_display, pi.generic_name, pi.dose, pi.frequency, pi.timing,
                           pi.long_term, pi.duration_days, e.started_at, s.name AS prescribed_by,
                           (e.started_at AT TIME ZONE 'Asia/Kolkata')::date AS started_on,
                           row_number() OVER (PARTITION BY %s ORDER BY e.started_at DESC, pi.line_no) AS rn
                      FROM prescription_item pi
                      JOIN prescription p ON p.id = pi.prescription_id
                      JOIN encounter e ON e.id = p.encounter_id
                      JOIN staff_user s ON s.id = e.doctor_id
                     WHERE p.profile_id = :profileId AND e.clinic_id = :clinicId AND e.status = 'SIGNED'
                )
                SELECT i.medication_key, i.drug_display, i.generic_name, i.dose, i.frequency, i.timing, i.long_term,
                       i.started_on,
                       CASE WHEN i.long_term THEN NULL ELSE i.started_on + i.duration_days - 1 END AS ends_on,
                       i.prescribed_by
                  FROM items i
                 WHERE i.rn = 1
                   AND (i.long_term OR i.started_on + i.duration_days - 1 >= (now() AT TIME ZONE 'Asia/Kolkata')::date)
                   AND NOT EXISTS (SELECT 1 FROM medication_stop ms
                                    WHERE ms.profile_id = :profileId AND ms.clinic_id = :clinicId
                                      AND ms.medication_key = i.medication_key AND ms.stopped_at >= i.started_at)
                 ORDER BY i.long_term DESC, i.started_on DESC, i.drug_display
                """.formatted(MEDICATION_KEY, MEDICATION_KEY))
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .query(CurrentMedicine.class)
                .list();
    }

    List<VitalValue> latestVitals(long profileId, long clinicId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (v.code) v.code, v.display, v.value, v.unit, v.observed_at
                  FROM vital_sign v JOIN encounter e ON e.id = v.encounter_id
                 WHERE v.profile_id = :profileId AND e.clinic_id = :clinicId AND e.status = 'SIGNED'
                 ORDER BY v.code, v.observed_at DESC
                """)
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .query(VitalValue.class)
                .list();
    }

    Optional<LastVisit> lastVisit(long profileId, long clinicId) {
        return jdbc.sql("""
                SELECT e.id AS encounter_id, e.started_at AS date, s.name AS doctor_name,
                       coalesce((SELECT string_agg(d.display, '; ' ORDER BY d.line_no) FROM diagnosis d
                                  WHERE d.encounter_id = e.id), '') AS diagnoses
                  FROM encounter e JOIN staff_user s ON s.id = e.doctor_id
                 WHERE e.profile_id = :profileId AND e.clinic_id = :clinicId AND e.status = 'SIGNED'
                 ORDER BY e.started_at DESC
                 LIMIT 1
                """)
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .query(LastVisit.class)
                .optional();
    }

    /** Up to the last 30 readings per vital, oldest first, ready for charts. */
    public List<VitalSeries> vitalTrends(ClinicContext ctx, UUID patientId, String code) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        record Row(String code, String display, String unit, Instant observedAt, BigDecimal value, UUID encounterId) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT code, display, unit, observed_at, value, encounter_id FROM (
                    SELECT v.code, v.display, v.unit, v.observed_at, v.value, v.encounter_id,
                           row_number() OVER (PARTITION BY v.code ORDER BY v.observed_at DESC) AS rn
                      FROM vital_sign v JOIN encounter e ON e.id = v.encounter_id
                     WHERE v.profile_id = :profileId AND e.clinic_id = :clinicId AND e.status = 'SIGNED'
                       AND (CAST(:code AS text) IS NULL OR v.code = :code)
                ) t
                 WHERE rn <= 30
                 ORDER BY code, observed_at
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("code", code)
                .query(Row.class)
                .list();
        Map<String, VitalSeries> series = new LinkedHashMap<>();
        for (Row r : rows) {
            series.computeIfAbsent(r.code(), k -> new VitalSeries(r.code(), r.display(), r.unit(), new ArrayList<>()))
                    .points().add(new VitalPoint(r.observedAt(), r.value(), r.encounterId()));
        }
        return List.copyOf(series.values());
    }

    @Transactional
    public PatientSummary resolveProblem(ClinicContext ctx, UUID patientId, String problemKey) {
        ctx.require(ClinicRole.DOCTOR);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        if (activeProblems(profileId, ctx.clinicId()).stream().noneMatch(p -> p.problemKey().equals(problemKey))) {
            throw ApiException.notFound("Active problem");
        }
        jdbc.sql("""
                INSERT INTO problem_status (profile_id, clinic_id, problem_key, status, changed_by)
                VALUES (:profileId, :clinicId, :key, 'RESOLVED', :staffId)
                ON CONFLICT (profile_id, clinic_id, problem_key) DO UPDATE
                   SET status = 'RESOLVED', changed_by = EXCLUDED.changed_by, changed_at = now()
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("key", problemKey)
                .param("staffId", ctx.staffId())
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "PROBLEM_RESOLVED", "patient_profile", String.valueOf(profileId),
                Map.of("problemKey", problemKey));
        return summary(ctx, patientId);
    }

    @Transactional
    public PatientSummary stopMedicine(ClinicContext ctx, UUID patientId, String medicationKey, String reason) {
        ctx.require(ClinicRole.DOCTOR);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        if (currentMedicines(profileId, ctx.clinicId()).stream().noneMatch(m -> m.medicationKey().equals(medicationKey))) {
            throw ApiException.notFound("Current medicine");
        }
        jdbc.sql("""
                INSERT INTO medication_stop (profile_id, clinic_id, medication_key, reason, stopped_by)
                VALUES (:profileId, :clinicId, :key, :reason, :staffId)
                ON CONFLICT (profile_id, clinic_id, medication_key) DO UPDATE
                   SET reason = EXCLUDED.reason, stopped_by = EXCLUDED.stopped_by, stopped_at = now()
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("key", medicationKey)
                .param("reason", reason)
                .param("staffId", ctx.staffId())
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "MEDICINE_STOPPED", "patient_profile", String.valueOf(profileId),
                Map.of("medicationKey", medicationKey, "reason", reason == null ? "" : reason));
        return summary(ctx, patientId);
    }

    /** Prescription from the most recent signed visit at this clinic, for "repeat last prescription". */
    public LastPrescription lastPrescription(ClinicContext ctx, UUID patientId) {
        ctx.require(ClinicRole.DOCTOR);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        record Head(UUID encounterId, Instant date, String doctorName, UUID prescriptionId) {
        }
        Head head = jdbc.sql("""
                SELECT e.id AS encounter_id, e.started_at AS date, s.name AS doctor_name, p.id AS prescription_id
                  FROM encounter e
                  JOIN prescription p ON p.encounter_id = e.id
                  JOIN staff_user s ON s.id = e.doctor_id
                 WHERE e.profile_id = :profileId AND e.clinic_id = :clinicId AND e.status = 'SIGNED'
                 ORDER BY e.started_at DESC
                 LIMIT 1
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .query(Head.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Previous prescription"));
        List<RxItem> items = jdbc.sql("""
                SELECT drug_id, drug_display, generic_name, dose, frequency, route, timing, duration_days, long_term, instructions
                  FROM prescription_item WHERE prescription_id = :id ORDER BY line_no
                """)
                .param("id", head.prescriptionId())
                .query(RxItem.class)
                .list();
        return new LastPrescription(head.encounterId(), head.date(), head.doctorName(), items);
    }
}
