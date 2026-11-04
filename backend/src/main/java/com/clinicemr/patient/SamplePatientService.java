package com.clinicemr.patient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.clinicemr.clinical.EncounterContent;
import com.clinicemr.clinical.EncounterDtos.SaveDraftRequest;
import com.clinicemr.clinical.EncounterDtos.StartEncounterRequest;
import com.clinicemr.clinical.EncounterService;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A practice patient for a new clinic: a finished visit with vitals, diagnosis, medicines and advice to look at,
 * and room to start a visit of their own. It has no mobile number, so it never reaches the patient app or another
 * clinic; it is left out of visit counts, and Remove hides it like any deleted patient.
 */
@Service
public class SamplePatientService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final PatientService patients;
    private final EncounterService encounters;
    private final AuditService audit;

    public SamplePatientService(JdbcClient jdbc, PatientService patients, EncounterService encounters, AuditService audit) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.encounters = encounters;
        this.audit = audit;
    }

    /** The clinic's sample patient, made on first use (asking again returns the same one). */
    @Transactional
    public UUID open(ClinicContext ctx) {
        ctx.require(ClinicRole.ADMIN, ClinicRole.DOCTOR, ClinicRole.NURSE, ClinicRole.FRONT_DESK);
        Optional<UUID> existing = jdbc.sql("""
                SELECT p.public_id FROM patient_profile p
                  JOIN patient_identifier pi ON pi.profile_id = p.id AND pi.clinic_id = :clinicId
                 WHERE p.sample AND p.deleted_at IS NULL
                 LIMIT 1
                """)
                .param("clinicId", ctx.clinicId())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDate today = LocalDate.now(IST);
        long profileId = jdbc.sql("""
                INSERT INTO patient_profile (name, dob, dob_estimated, gender, blood_group, sample, created_by)
                VALUES ('Sunita Sharma (sample)', :dob, true, 'F', 'B+', true, :createdBy)
                RETURNING id
                """)
                .param("dob", today.minusYears(42).withDayOfYear(1))
                .param("createdBy", "staff:" + ctx.staffId())
                .query(Long.class)
                .single();
        patients.linkToClinic(ctx, profileId);
        UUID publicId = jdbc.sql("SELECT public_id FROM patient_profile WHERE id = :id").param("id", profileId)
                .query(UUID.class).single();

        // The finished visit needs a doctor: the person asking, or else the clinic's first doctor
        doctorContext(ctx).ifPresent(doctor -> {
            UUID visitId = UUID.randomUUID();
            encounters.start(doctor, new StartEncounterRequest(visitId, publicId, "OPD"));
            encounters.saveDraft(doctor, visitId, new SaveDraftRequest(0, visit(today)));
            encounters.sign(doctor, visitId);
        });
        audit.staff(ctx.staffId(), ctx.clinicId(), "SAMPLE_PATIENT_CREATED", "patient_profile", String.valueOf(profileId), Map.of());
        return publicId;
    }

    /** Hides the sample patient (and with it, its visits and any practice payments). */
    @Transactional
    public void remove(ClinicContext ctx) {
        ctx.require(ClinicRole.ADMIN, ClinicRole.DOCTOR, ClinicRole.NURSE, ClinicRole.FRONT_DESK);
        int n = jdbc.sql("""
                UPDATE patient_profile p SET deleted_at = now()
                  FROM patient_identifier pi
                 WHERE pi.profile_id = p.id AND pi.clinic_id = :clinicId AND p.sample AND p.deleted_at IS NULL
                """)
                .param("clinicId", ctx.clinicId())
                .update();
        if (n > 0) {
            audit.staff(ctx.staffId(), ctx.clinicId(), "SAMPLE_PATIENT_REMOVED", "clinic", String.valueOf(ctx.clinicId()), Map.of());
        }
    }

    private Optional<ClinicContext> doctorContext(ClinicContext ctx) {
        if (ctx.has(ClinicRole.DOCTOR)) {
            return Optional.of(ctx);
        }
        return jdbc.sql("""
                SELECT m.staff_id FROM clinic_member m JOIN doctor d ON d.staff_id = m.staff_id
                 WHERE m.clinic_id = :clinicId AND m.role = 'DOCTOR'
                 ORDER BY m.staff_id LIMIT 1
                """)
                .param("clinicId", ctx.clinicId())
                .query(Long.class)
                .optional()
                .map(staffId -> new ClinicContext(staffId, ctx.clinicId(), Set.of(ClinicRole.DOCTOR)));
    }

    private static EncounterContent visit(LocalDate today) {
        return new EncounterContent(
                "Fever with body ache for 3 days. Mild sore throat. No cough or breathlessness.",
                null,
                "No known long-term illness.",
                "Throat congested. Chest clear.",
                null,
                "Drink plenty of water and fluids. Rest for 2 days. Come back early if fever goes above 102°F or lasts more than 3 more days.",
                null,
                new EncounterContent.Vitals(124, 82, 92, new BigDecimal("100.8"), 98, new BigDecimal("64"), null, null, null),
                List.of(new EncounterContent.DiagnosisEntry(null, null, "Viral fever", "PROVISIONAL", false)),
                List.of(
                        new EncounterContent.RxItem(null, "Paracetamol 650 mg tablet", "Paracetamol", "1 tablet", "1-1-1",
                                null, "after food", 3, false, "Only if fever"),
                        new EncounterContent.RxItem(null, "Cetirizine 10 mg tablet", "Cetirizine", "1 tablet", "0-0-1",
                                null, "after food", 5, false, null)),
                List.of(new EncounterContent.InvestigationEntry(null, "Complete blood count (CBC)")),
                new EncounterContent.FollowUpEntry(today.plusDays(5), "Review with CBC report"));
    }
}
