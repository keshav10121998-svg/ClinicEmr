package com.clinicemr.clinical;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.clinicemr.clinical.EncounterDtos.AmendRequest;
import com.clinicemr.clinical.EncounterDtos.ApplyTemplateRequest;
import com.clinicemr.history.AllergyService;
import com.clinicemr.productivity.FavouriteService;
import com.clinicemr.productivity.TemplateService;
import com.clinicemr.clinical.EncounterDtos.DoctorRef;
import com.clinicemr.clinical.EncounterDtos.EncounterListItem;
import com.clinicemr.clinical.EncounterDtos.EncounterView;
import com.clinicemr.clinical.EncounterDtos.SaveDraftRequest;
import com.clinicemr.clinical.EncounterDtos.StartEncounterRequest;
import com.clinicemr.clinical.EncounterDtos.VersionInfo;
import com.clinicemr.patient.PatientDtos.PatientView;
import com.clinicemr.patient.PatientService;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Visit lifecycle: DRAFT (autosaved, versioned) → SIGNED (immutable snapshot + hash) → amended (new snapshot with
 * reason; the original is kept). Only the visit's own doctor can sign or amend; nurses can help with drafts.
 */
@Service
public class EncounterService {

    static final int MAX_DRAFT_BYTES = 256 * 1024;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Validator validator;
    private final PatientService patients;
    private final ClinicalRowsWriter rows;
    private final SnapshotHasher hasher;
    private final AuditService audit;
    private final AllergyService allergies;
    private final FavouriteService favourites;
    private final TemplateService templates;

    public EncounterService(JdbcClient jdbc, ObjectMapper json, Validator validator, PatientService patients,
                            ClinicalRowsWriter rows, SnapshotHasher hasher, AuditService audit,
                            AllergyService allergies, FavouriteService favourites, TemplateService templates) {
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
        this.patients = patients;
        this.rows = rows;
        this.hasher = hasher;
        this.audit = audit;
        this.allergies = allergies;
        this.favourites = favourites;
        this.templates = templates;
    }

    record EncounterRow(UUID id, long profileId, long clinicId, long doctorId, String doctorName, String visitType,
                        String status, String draft, int draftVersion, int currentVersion, Instant startedAt,
                        Instant signedAt) {
    }

    private static final String SELECT_ENCOUNTER = """
            SELECT e.id, e.profile_id, e.clinic_id, e.doctor_id, s.name AS doctor_name, e.visit_type, e.status,
                   e.draft::text AS draft, e.draft_version, e.current_version, e.started_at, e.signed_at
              FROM encounter e JOIN staff_user s ON s.id = e.doctor_id
             WHERE e.id = :id AND e.clinic_id = :clinicId
            """;

    // ---------------------------------------------------------------- drafts

    @Transactional
    public EncounterView start(ClinicContext ctx, StartEncounterRequest req) {
        ctx.require(ClinicRole.DOCTOR);
        long profileId = patients.requireLinkedProfileId(ctx, req.patientId());

        Optional<EncounterRow> existing = find(ctx, req.id(), false);
        if (existing.isPresent()) {
            // Retried start (e.g. from an offline client): return the same visit if it matches
            if (existing.get().profileId() != profileId) {
                throw new ApiException(HttpStatus.CONFLICT, "ID_IN_USE", "This visit id is already used for another patient.");
            }
            return view(ctx, existing.get());
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM encounter WHERE id = :id)").param("id", req.id())
                .query(Boolean.class).single()) {
            throw new ApiException(HttpStatus.CONFLICT, "ID_IN_USE", "This visit id is already in use.");
        }

        jdbc.sql("""
                INSERT INTO encounter (id, profile_id, clinic_id, doctor_id, visit_type, created_by)
                VALUES (:id, :profileId, :clinicId, :doctorId, :visitType, :createdBy)
                """)
                .param("id", req.id())
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("doctorId", ctx.staffId())
                .param("visitType", req.visitType() == null ? "OPD" : req.visitType())
                .param("createdBy", ctx.staffId())
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "ENCOUNTER_STARTED", "encounter", req.id().toString(), Map.of());
        return view(ctx, find(ctx, req.id(), false).orElseThrow());
    }

    @Transactional
    public EncounterView saveDraft(ClinicContext ctx, UUID id, SaveDraftRequest req) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        EncounterRow e = find(ctx, id, true).orElseThrow(() -> ApiException.notFound("Visit"));
        requireDraft(e);
        if (!ctx.has(ClinicRole.NURSE) && e.doctorId() != ctx.staffId()) {
            throw ApiException.forbidden("NOT_AUTHOR", "Only the visit's doctor or a nurse can edit this draft.");
        }
        if (req.baseVersion() != e.draftVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "DRAFT_VERSION_CONFLICT",
                    "This visit was changed elsewhere. Reload it before saving.",
                    Map.of("currentDraftVersion", e.draftVersion()));
        }
        if (req.content() == null) {
            throw ApiException.badRequest("CONTENT_REQUIRED", "Send the visit content to save.");
        }
        writeDraft(id, req.content());
        return view(ctx, find(ctx, id, false).orElseThrow());
    }

    /** Fills the draft from one of the doctor's templates without overwriting what's already entered. */
    @Transactional
    public EncounterView applyTemplate(ClinicContext ctx, UUID id, ApplyTemplateRequest req) {
        ctx.require(ClinicRole.DOCTOR);
        EncounterRow e = find(ctx, id, true).orElseThrow(() -> ApiException.notFound("Visit"));
        requireDraft(e);
        requireAuthor(ctx, e);
        if (req.baseVersion() != e.draftVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "DRAFT_VERSION_CONFLICT",
                    "This visit was changed elsewhere. Reload it before applying a template.",
                    Map.of("currentDraftVersion", e.draftVersion()));
        }
        var template = templates.use(ctx.staffId(), req.templateId());
        EncounterContent merged = TemplateService.merge(fromJson(e.draft()), template, LocalDate.ofInstant(e.startedAt(), IST));
        writeDraft(id, merged);
        return view(ctx, find(ctx, id, false).orElseThrow());
    }

    private void writeDraft(UUID id, EncounterContent content) {
        String draftJson = toJson(content);
        if (draftJson.length() > MAX_DRAFT_BYTES) {
            throw ApiException.badRequest("DRAFT_TOO_LARGE", "This visit has too much text to save.");
        }
        jdbc.sql("""
                UPDATE encounter SET draft = CAST(:draft AS jsonb), draft_version = draft_version + 1, updated_at = now()
                 WHERE id = :id
                """)
                .param("draft", draftJson)
                .param("id", id)
                .update();
    }

    @Transactional
    public EncounterView cancel(ClinicContext ctx, UUID id) {
        ctx.require(ClinicRole.DOCTOR);
        EncounterRow e = find(ctx, id, true).orElseThrow(() -> ApiException.notFound("Visit"));
        requireDraft(e);
        requireAuthor(ctx, e);
        jdbc.sql("UPDATE encounter SET status = 'CANCELLED', updated_at = now() WHERE id = :id").param("id", id).update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "ENCOUNTER_CANCELLED", "encounter", id.toString(), Map.of());
        return view(ctx, find(ctx, id, false).orElseThrow());
    }

    // ---------------------------------------------------------------- sign and amend

    @Transactional
    public EncounterView sign(ClinicContext ctx, UUID id) {
        ctx.require(ClinicRole.DOCTOR);
        EncounterRow e = find(ctx, id, true).orElseThrow(() -> ApiException.notFound("Visit"));
        requireAuthor(ctx, e);
        if ("SIGNED".equals(e.status())) {
            return view(ctx, e);   // repeated click: already signed, nothing to do
        }
        requireDraft(e);

        EncounterContent content = fromJson(e.draft());
        validateForSigning(content, e.startedAt());

        Instant signedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        SignedSnapshot snapshot = buildSnapshot(ctx, e, 1, signedAt, null, content);
        storeVersion(e.id(), snapshot, ctx.staffId(), null, null);
        rows.replace(e.id(), e.profileId(), e.clinicId(), e.startedAt(), content);
        favourites.recordUse(ctx.staffId(), content);

        jdbc.sql("""
                UPDATE encounter SET status = 'SIGNED', signed_at = :signedAt, current_version = 1,
                                     draft_version = draft_version + 1, updated_at = now()
                 WHERE id = :id
                """)
                .param("signedAt", java.sql.Timestamp.from(signedAt))
                .param("id", id)
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "ENCOUNTER_SIGNED", "encounter", id.toString(),
                Map.of("version", 1, "hash", hasher.hash(snapshot)));
        return view(ctx, find(ctx, id, false).orElseThrow());
    }

    @Transactional
    public EncounterView amend(ClinicContext ctx, UUID id, AmendRequest req) {
        ctx.require(ClinicRole.DOCTOR);
        EncounterRow e = find(ctx, id, true).orElseThrow(() -> ApiException.notFound("Visit"));
        requireAuthor(ctx, e);
        if (!"SIGNED".equals(e.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SIGNED", "Only signed visits can be amended. Edit the draft instead.");
        }
        validateForSigning(req.content(), e.startedAt());

        SignedSnapshot previous = loadSnapshot(e.id(), e.currentVersion());
        List<String> changed = changedFields(previous.content(), req.content());
        if (changed.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CHANGES", "The amendment doesn't change anything.");
        }

        int version = e.currentVersion() + 1;
        Instant amendedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String reason = req.reason().trim();
        SignedSnapshot snapshot = buildSnapshot(ctx, e, version, amendedAt, reason, req.content());
        storeVersion(e.id(), snapshot, ctx.staffId(), reason, changed);
        rows.replace(e.id(), e.profileId(), e.clinicId(), e.startedAt(), req.content());

        jdbc.sql("""
                UPDATE encounter SET draft = CAST(:draft AS jsonb), current_version = :version,
                                     draft_version = draft_version + 1, updated_at = now()
                 WHERE id = :id
                """)
                .param("draft", toJson(req.content()))
                .param("version", version)
                .param("id", id)
                .update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "ENCOUNTER_AMENDED", "encounter", id.toString(),
                Map.of("version", version, "reason", reason, "changedFields", changed));
        return view(ctx, find(ctx, id, false).orElseThrow());
    }

    // ---------------------------------------------------------------- reads

    public EncounterView get(ClinicContext ctx, UUID id) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        return view(ctx, find(ctx, id, false).orElseThrow(() -> ApiException.notFound("Visit")));
    }

    /** Signed snapshot of the latest version, for printing. Any staff role may print. */
    public SignedSnapshot latestSnapshot(ClinicContext ctx, UUID id) {
        EncounterRow e = find(ctx, id, false).orElseThrow(() -> ApiException.notFound("Visit"));
        if (!"SIGNED".equals(e.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SIGNED", "The prescription is available after the doctor signs the visit.");
        }
        return loadSnapshot(e.id(), e.currentVersion());
    }

    /** Re-hashes every stored version and reports whether all still match. */
    public boolean verifyIntegrity(ClinicContext ctx, UUID id) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.ADMIN);
        find(ctx, id, false).orElseThrow(() -> ApiException.notFound("Visit"));
        record Row(String snapshot, String contentHash) {
        }
        return jdbc.sql("SELECT snapshot::text AS snapshot, content_hash FROM encounter_version WHERE encounter_id = :id")
                .param("id", id)
                .query(Row.class)
                .list().stream()
                .allMatch(r -> hasher.hash(hasher.fromJson(r.snapshot())).equals(r.contentHash()));
    }

    public List<EncounterListItem> listForPatient(ClinicContext ctx, UUID patientId) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        return jdbc.sql("""
                SELECT e.id, e.visit_type, e.started_at, e.signed_at, s.name AS doctor_name, e.current_version,
                       coalesce((SELECT string_agg(d.display, '; ' ORDER BY d.line_no) FROM diagnosis d
                                  WHERE d.encounter_id = e.id), '') AS diagnoses,
                       (SELECT count(*) FROM prescription_item pi JOIN prescription p ON p.id = pi.prescription_id
                         WHERE p.encounter_id = e.id)::int AS prescription_lines
                  FROM encounter e JOIN staff_user s ON s.id = e.doctor_id
                 WHERE e.profile_id = :profileId AND e.clinic_id = :clinicId AND e.status = 'SIGNED'
                 ORDER BY e.started_at DESC
                 LIMIT 100
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .query(EncounterListItem.class)
                .list();
    }

    // ---------------------------------------------------------------- helpers

    private Optional<EncounterRow> find(ClinicContext ctx, UUID id, boolean forUpdate) {
        String sql = forUpdate ? SELECT_ENCOUNTER + " FOR UPDATE OF e" : SELECT_ENCOUNTER;
        return jdbc.sql(sql)
                .param("id", id)
                .param("clinicId", ctx.clinicId())
                .query(EncounterRow.class)
                .optional();
    }

    private static void requireDraft(EncounterRow e) {
        if ("SIGNED".equals(e.status())) {
            throw new ApiException(HttpStatus.LOCKED, "ENCOUNTER_SIGNED", "This visit is signed. Use amend to correct it.");
        }
        if ("CANCELLED".equals(e.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ENCOUNTER_CANCELLED", "This visit was cancelled.");
        }
    }

    private static void requireAuthor(ClinicContext ctx, EncounterRow e) {
        if (e.doctorId() != ctx.staffId()) {
            throw ApiException.forbidden("NOT_AUTHOR", "Only the doctor who saw the patient can do this.");
        }
    }

    private void validateForSigning(EncounterContent c, Instant startedAt) {
        Map<String, String> problems = new LinkedHashMap<>();
        for (ConstraintViolation<EncounterContent> v : validator.validate(c)) {
            problems.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        if (c.diagnoses().isEmpty()) {
            problems.put("diagnoses", "add at least one diagnosis");
        }
        for (int i = 0; i < c.prescription().size(); i++) {
            var item = c.prescription().get(i);
            if (item.durationDays() == null && !item.longTerm()) {
                problems.putIfAbsent("prescription[" + i + "].durationDays", "enter a duration or mark as long-term");
            }
        }
        LocalDate visitDate = LocalDate.ofInstant(startedAt, IST);
        if (c.followUp() != null && c.followUp().dueDate() != null && !c.followUp().dueDate().isAfter(visitDate)) {
            problems.put("followUp.dueDate", "must be after the visit date");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_REQUIRED_FIELDS",
                    "Fix these before signing.", Map.of("fields", problems));
        }
    }

    private SignedSnapshot buildSnapshot(ClinicContext ctx, EncounterRow e, int version, Instant signedAt,
                                         String reason, EncounterContent content) {
        PatientView p = patients.getByProfileId(ctx, e.profileId());
        SignedSnapshot.ClinicInfo clinic = jdbc.sql("""
                SELECT id, name, address, city, state, pincode, phone FROM clinic WHERE id = :id
                """)
                .param("id", e.clinicId())
                .query(SignedSnapshot.ClinicInfo.class)
                .single();
        SignedSnapshot.DoctorInfo doctor = jdbc.sql("""
                SELECT s.id AS staff_id, s.name, d.qualifications, d.registration_no, d.registration_council, d.specialty
                  FROM staff_user s JOIN doctor d ON d.staff_id = s.id WHERE s.id = :id
                """)
                .param("id", e.doctorId())
                .query(SignedSnapshot.DoctorInfo.class)
                .single();
        return new SignedSnapshot(e.id(), version, e.visitType(), e.startedAt().truncatedTo(ChronoUnit.MILLIS),
                signedAt, reason,
                new SignedSnapshot.PatientInfo(p.publicId(), p.mrn(), p.name(), p.gender(), p.dob(), p.dobEstimated(), p.ageYears()),
                clinic, doctor, content);
    }

    private void storeVersion(UUID encounterId, SignedSnapshot snapshot, long signedBy, String reason, List<String> changed) {
        jdbc.sql("""
                INSERT INTO encounter_version (encounter_id, version, snapshot, content_hash, signed_by, signed_at,
                                               amendment_reason, diff)
                VALUES (:id, :version, CAST(:snapshot AS jsonb), :hash, :signedBy, :signedAt, :reason, CAST(:diff AS jsonb))
                """)
                .param("id", encounterId)
                .param("version", snapshot.version())
                .param("snapshot", hasher.toCanonicalJson(snapshot))
                .param("hash", hasher.hash(snapshot))
                .param("signedBy", signedBy)
                .param("signedAt", java.sql.Timestamp.from(snapshot.signedAt()))
                .param("reason", reason)
                .param("diff", changed == null ? null : toJson(Map.of("changedFields", changed)))
                .update();
    }

    private SignedSnapshot loadSnapshot(UUID encounterId, int version) {
        String snapshot = jdbc.sql("""
                SELECT snapshot::text FROM encounter_version WHERE encounter_id = :id AND version = :version
                """)
                .param("id", encounterId)
                .param("version", version)
                .query(String.class)
                .single();
        return hasher.fromJson(snapshot);
    }

    /** Top-level content fields that differ between two versions. */
    List<String> changedFields(EncounterContent before, EncounterContent after) {
        JsonNode a = json.valueToTree(before);
        JsonNode b = json.valueToTree(after);
        Set<String> names = new TreeSet<>();
        a.fieldNames().forEachRemaining(names::add);
        b.fieldNames().forEachRemaining(names::add);
        List<String> changed = new ArrayList<>();
        for (Iterator<String> it = names.iterator(); it.hasNext(); ) {
            String name = it.next();
            JsonNode x = a.get(name);
            JsonNode y = b.get(name);
            boolean xEmpty = x == null || x.isNull() || (x.isArray() && x.isEmpty());
            boolean yEmpty = y == null || y.isNull() || (y.isArray() && y.isEmpty());
            if (xEmpty && yEmpty) {
                continue;
            }
            if (xEmpty != yEmpty || !x.equals(y)) {
                changed.add(name);
            }
        }
        return changed;
    }

    private EncounterView view(ClinicContext ctx, EncounterRow e) {
        record VersionRow(int version, Instant signedAt, String amendmentReason, String diff, String contentHash) {
        }
        List<VersionInfo> versions = jdbc.sql("""
                SELECT version, signed_at, amendment_reason, diff::text AS diff, content_hash
                  FROM encounter_version WHERE encounter_id = :id ORDER BY version
                """)
                .param("id", e.id())
                .query(VersionRow.class)
                .list().stream()
                .map(v -> new VersionInfo(v.version(), v.signedAt(), v.amendmentReason(), changedFrom(v.diff()), v.contentHash()))
                .toList();
        UUID patientPublicId = patients.getByProfileId(ctx, e.profileId()).publicId();
        EncounterContent content = fromJson(e.draft());
        List<PrescriptionSafety.Warning> warnings =
                PrescriptionSafety.check(content, allergies.activeSubstances(e.profileId(), e.clinicId()));
        return new EncounterView(e.id(), patientPublicId, e.status(), e.visitType(), new DoctorRef(e.doctorId(), e.doctorName()),
                e.startedAt(), e.signedAt(), e.draftVersion(), e.currentVersion(), content, versions, warnings);
    }

    private List<String> changedFrom(String diff) {
        if (diff == null) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            json.readTree(diff).path("changedFields").forEach(n -> out.add(n.asText()));
            return out;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private EncounterContent fromJson(String draft) {
        try {
            EncounterContent c = json.readValue(draft, EncounterContent.class);
            return c == null ? EncounterContent.empty() : c;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored draft can't be read", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
