package com.clinicemr.patient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.patient.PatientDtos.DuplicateCandidate;
import com.clinicemr.patient.PatientDtos.PatientView;
import com.clinicemr.patient.PatientDtos.RegisterPatientRequest;
import com.clinicemr.shared.PhoneNumbers;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient registration and lookup within one clinic.
 * <p>
 * A clinic only ever sees profiles linked to it through {@code patient_identifier}. Lookups never reveal
 * whether a phone number or person is registered elsewhere on the platform; linking an existing platform
 * profile will go through patient approval (OTP or app), which is a later step.
 */
@Service
public class PatientService {

    // pg_trgm similarity above which two names at the same phone number are treated as possibly the same person
    static final double DUPLICATE_NAME_SIMILARITY = 0.45;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public PatientService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private record PatientRow(long id, UUID publicId, String mrn, String name, String gender, LocalDate dob,
                              boolean dobEstimated, String primaryPhoneE164, String bloodGroup, String abhaNumber) {
        PatientView toView() {
            return new PatientView(publicId, mrn, name, gender, dob, dobEstimated, PatientDtos.age(dob),
                    primaryPhoneE164, bloodGroup, abhaNumber != null);
        }
    }

    private static final String SELECT_LINKED = """
            SELECT p.id, p.public_id, pi.mrn, p.name, p.gender, p.dob, p.dob_estimated,
                   p.primary_phone_e164, p.blood_group, p.abha_number
              FROM patient_profile p
              JOIN patient_identifier pi ON pi.profile_id = p.id AND pi.clinic_id = :clinicId
             WHERE p.deleted_at IS NULL AND p.merged_into_id IS NULL
            """;

    @Transactional
    public PatientView register(ClinicContext ctx, RegisterPatientRequest req) {
        String phone = PhoneNumbers.toE164(req.phone());
        String name = req.name().trim().replaceAll("\\s+", " ");
        LocalDate dob = req.dob();
        boolean dobEstimated = false;
        if (dob == null) {
            if (req.ageYears() == null) {
                throw ApiException.badRequest("DOB_OR_AGE_REQUIRED", "Enter a date of birth or an age.");
            }
            dob = LocalDate.now().minusYears(req.ageYears());
            dobEstimated = true;
        }
        String abha = req.abhaNumber() == null ? null : req.abhaNumber().replace("-", "");

        if (abha != null && jdbc.sql("SELECT EXISTS (SELECT 1 FROM patient_profile WHERE abha_number = :abha)")
                .param("abha", abha).query(Boolean.class).single()) {
            throw new ApiException(HttpStatus.CONFLICT, "ABHA_ALREADY_REGISTERED",
                    "A profile with this ABHA number already exists. Ask the patient to approve access to link it.");
        }

        if (!req.confirmNew()) {
            List<DuplicateCandidate> candidates = findDuplicates(ctx.clinicId(), phone, name, req.gender(), dob);
            if (!candidates.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "POSSIBLE_DUPLICATE",
                        "This clinic already has a similar patient with this phone number. "
                                + "Open the existing record, or resend with confirmNew = true to register a new patient.",
                        Map.of("candidates", candidates));
            }
        }

        long profileId = jdbc.sql("""
                INSERT INTO patient_profile (name, dob, dob_estimated, gender, blood_group, abha_number,
                                             primary_phone_e164, created_by)
                VALUES (:name, :dob, :dobEstimated, :gender, :bloodGroup, :abha, :phone, :createdBy)
                RETURNING id
                """)
                .param("name", name)
                .param("dob", dob)
                .param("dobEstimated", dobEstimated)
                .param("gender", req.gender())
                .param("bloodGroup", req.bloodGroup())
                .param("abha", abha)
                .param("phone", phone)
                .param("createdBy", "staff:" + ctx.staffId())
                .query(Long.class)
                .single();

        String mrn = nextMrn(ctx.clinicId());
        jdbc.sql("INSERT INTO patient_identifier (profile_id, clinic_id, mrn) VALUES (:profileId, :clinicId, :mrn)")
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("mrn", mrn)
                .update();

        audit.staff(ctx.staffId(), ctx.clinicId(), "PATIENT_REGISTERED", "patient_profile",
                String.valueOf(profileId), Map.of("mrn", mrn));
        return loadLinked(ctx.clinicId(), profileId).toView();
    }

    /** Patients of this clinic with the given phone number (the whole family registered here). */
    public List<PatientView> findByPhone(ClinicContext ctx, String rawPhone) {
        String phone = PhoneNumbers.toE164(rawPhone);
        return jdbc.sql(SELECT_LINKED + " AND p.primary_phone_e164 = :phone ORDER BY p.name")
                .param("clinicId", ctx.clinicId())
                .param("phone", phone)
                .query(PatientRow.class)
                .list().stream().map(PatientRow::toView).toList();
    }

    /** Search this clinic's patients by MRN (exact) or name (fuzzy). */
    public List<PatientView> search(ClinicContext ctx, String q) {
        String term = q == null ? "" : q.trim();
        if (term.length() < 2) {
            throw ApiException.badRequest("QUERY_TOO_SHORT", "Type at least 2 characters.");
        }
        return jdbc.sql(SELECT_LINKED + """
                 AND (pi.mrn = :term OR p.name ILIKE '%' || :term || '%' OR similarity(p.name, :term) > 0.3)
                 ORDER BY (pi.mrn = :term) DESC, similarity(p.name, :term) DESC
                 LIMIT 20
                """)
                .param("clinicId", ctx.clinicId())
                .param("term", term)
                .query(PatientRow.class)
                .list().stream().map(PatientRow::toView).toList();
    }

    public PatientView get(ClinicContext ctx, UUID publicId) {
        return jdbc.sql(SELECT_LINKED + " AND p.public_id = :publicId")
                .param("clinicId", ctx.clinicId())
                .param("publicId", publicId)
                .query(PatientRow.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Patient"))
                .toView();
    }

    /**
     * Internal id of a patient linked to this clinic, or 404. Other modules use this before touching a
     * patient's data, so a clinic can never address a profile it isn't linked to.
     */
    public long requireLinkedProfileId(ClinicContext ctx, UUID publicId) {
        return jdbc.sql("""
                SELECT p.id FROM patient_profile p
                  JOIN patient_identifier pi ON pi.profile_id = p.id AND pi.clinic_id = :clinicId
                 WHERE p.public_id = :publicId AND p.deleted_at IS NULL AND p.merged_into_id IS NULL
                """)
                .param("clinicId", ctx.clinicId())
                .param("publicId", publicId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Patient"));
    }

    private List<DuplicateCandidate> findDuplicates(long clinicId, String phone, String name, String gender, LocalDate dob) {
        record Row(UUID publicId, String mrn, String name, String gender, LocalDate dob, double sim) {
        }
        return jdbc.sql(SELECT_LINKED.replace("SELECT p.id,", "SELECT similarity(p.name, :name) AS sim, p.id,") + """
                 AND p.primary_phone_e164 = :phone
                 AND p.gender = :gender
                 AND similarity(p.name, :name) >= :threshold
                 AND (p.dob IS NULL OR abs(p.dob - CAST(:dob AS date)) <= 366)
                 ORDER BY sim DESC
                """)
                .param("clinicId", clinicId)
                .param("phone", phone)
                .param("gender", gender)
                .param("name", name)
                .param("threshold", DUPLICATE_NAME_SIMILARITY)
                .param("dob", dob)
                .query(Row.class)
                .list().stream()
                .map(r -> new DuplicateCandidate(r.publicId(), r.mrn(), r.name(), r.gender(), PatientDtos.age(r.dob()),
                        Math.round(r.sim() * 100) / 100.0))
                .toList();
    }

    private PatientRow loadLinked(long clinicId, long profileId) {
        return jdbc.sql(SELECT_LINKED + " AND p.id = :id")
                .param("clinicId", clinicId)
                .param("id", profileId)
                .query(PatientRow.class)
                .single();
    }

    private String nextMrn(long clinicId) {
        int n = jdbc.sql("UPDATE clinic SET next_mrn = next_mrn + 1 WHERE id = :id RETURNING next_mrn - 1")
                .param("id", clinicId)
                .query(Integer.class)
                .single();
        return String.format("P%06d", n);
    }
}
