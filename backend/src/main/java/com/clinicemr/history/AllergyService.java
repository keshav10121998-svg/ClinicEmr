package com.clinicemr.history;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.clinicemr.history.HistoryDtos.AddAllergyRequest;
import com.clinicemr.history.HistoryDtos.AllergyItem;
import com.clinicemr.history.HistoryDtos.AllergyList;
import com.clinicemr.history.HistoryDtos.ChangeAllergyStatusRequest;
import com.clinicemr.patient.PatientService;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allergies recorded by this clinic. Allergies from other clinics will appear once cross-clinic access is in place.
 */
@Service
public class AllergyService {

    private final JdbcClient jdbc;
    private final PatientService patients;
    private final AuditService audit;

    public AllergyService(JdbcClient jdbc, PatientService patients, AuditService audit) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.audit = audit;
    }

    public AllergyList list(ClinicContext ctx, UUID patientId) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        return listFor(patients.requireLinkedProfileId(ctx, patientId), ctx.clinicId());
    }

    AllergyList listFor(long profileId, long clinicId) {
        List<AllergyItem> items = jdbc.sql("""
                SELECT a.id, a.category, a.substance, a.reaction, a.severity, a.status, s.name AS recorded_by, a.recorded_at
                  FROM allergy a JOIN staff_user s ON s.id = a.recorded_by
                 WHERE a.profile_id = :profileId AND a.clinic_id = :clinicId AND a.status = 'ACTIVE'
                 ORDER BY CASE a.severity WHEN 'SEVERE' THEN 0 WHEN 'MODERATE' THEN 1 ELSE 2 END, a.recorded_at
                """)
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .query(AllergyItem.class)
                .list();
        record Review(boolean noKnownAllergies, Instant reviewedAt) {
        }
        Optional<Review> review = jdbc.sql("""
                SELECT no_known_allergies, reviewed_at FROM allergy_review WHERE profile_id = :profileId AND clinic_id = :clinicId
                """)
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .query(Review.class)
                .optional();
        return new AllergyList(review.isPresent() || !items.isEmpty(),
                review.map(Review::noKnownAllergies).orElse(false) && items.isEmpty(),
                review.map(Review::reviewedAt).orElse(null), items);
    }

    /** Active allergy substances, for prescription warnings. */
    public List<String> activeSubstances(long profileId, long clinicId) {
        return jdbc.sql("SELECT substance FROM allergy WHERE profile_id = :profileId AND clinic_id = :clinicId AND status = 'ACTIVE'")
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .query(String.class)
                .list();
    }

    @Transactional
    public AllergyList add(ClinicContext ctx, UUID patientId, AddAllergyRequest req) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        String substance = req.substance().trim().replaceAll("\\s+", " ");
        boolean exists = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM allergy WHERE profile_id = :profileId AND clinic_id = :clinicId
                                AND status = 'ACTIVE' AND lower(substance) = lower(:substance))
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("substance", substance)
                .query(Boolean.class)
                .single();
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "ALLERGY_EXISTS", substance + " is already recorded as an allergy.");
        }
        UUID id = jdbc.sql("""
                INSERT INTO allergy (profile_id, clinic_id, category, substance, reaction, severity, recorded_by)
                VALUES (:profileId, :clinicId, :category, :substance, :reaction, :severity, :staffId)
                RETURNING id
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("category", req.category() == null ? "DRUG" : req.category())
                .param("substance", substance)
                .param("reaction", req.reaction())
                .param("severity", req.severity())
                .param("staffId", ctx.staffId())
                .query(UUID.class)
                .single();
        upsertReview(profileId, ctx, false);
        audit.staff(ctx.staffId(), ctx.clinicId(), "ALLERGY_ADDED", "allergy", id.toString(), Map.of("substance", substance));
        return listFor(profileId, ctx.clinicId());
    }

    @Transactional
    public AllergyList markNoKnownAllergies(ClinicContext ctx, UUID patientId) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        if (!activeSubstances(profileId, ctx.clinicId()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "HAS_ALLERGIES",
                    "This patient has recorded allergies. Mark them inactive first if they no longer apply.");
        }
        upsertReview(profileId, ctx, true);
        audit.staff(ctx.staffId(), ctx.clinicId(), "NO_KNOWN_ALLERGIES", "patient_profile", String.valueOf(profileId), Map.of());
        return listFor(profileId, ctx.clinicId());
    }

    @Transactional
    public AllergyList changeStatus(ClinicContext ctx, UUID patientId, UUID allergyId, ChangeAllergyStatusRequest req) {
        ctx.require(ClinicRole.DOCTOR);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        int updated = jdbc.sql("""
                UPDATE allergy SET status = :status, status_reason = :reason, updated_at = now()
                 WHERE id = :id AND profile_id = :profileId AND clinic_id = :clinicId AND status = 'ACTIVE'
                """)
                .param("status", req.status())
                .param("reason", req.reason().trim())
                .param("id", allergyId)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .update();
        if (updated == 0) {
            throw ApiException.notFound("Active allergy");
        }
        audit.staff(ctx.staffId(), ctx.clinicId(), "ALLERGY_" + req.status(), "allergy", allergyId.toString(),
                Map.of("reason", req.reason()));
        return listFor(profileId, ctx.clinicId());
    }

    private void upsertReview(long profileId, ClinicContext ctx, boolean noKnownAllergies) {
        jdbc.sql("""
                INSERT INTO allergy_review (profile_id, clinic_id, no_known_allergies, reviewed_by)
                VALUES (:profileId, :clinicId, :nka, :staffId)
                ON CONFLICT (profile_id, clinic_id) DO UPDATE
                   SET no_known_allergies = EXCLUDED.no_known_allergies, reviewed_by = EXCLUDED.reviewed_by, reviewed_at = now()
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("nka", noKnownAllergies)
                .param("staffId", ctx.staffId())
                .update();
    }
}
