package com.clinicemr.access;

import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.clinicemr.access.ClinicalAccessPolicy.EffectiveAccess;
import com.clinicemr.clinical.EncounterContent;
import com.clinicemr.clinical.SignedSnapshot;
import com.clinicemr.clinical.SnapshotHasher;
import com.clinicemr.patient.PatientService;
import com.clinicemr.records.storage.ObjectStorage;
import com.clinicemr.records.storage.StorageProperties;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records created at other clinics, shown to a clinic only within an active grant. Visits come from their latest
 * signed snapshot with the authoring doctor's private note removed. Every read is written to the access log.
 */
@Service
public class SharedHistoryService {

    private final JdbcClient jdbc;
    private final ClinicalAccessPolicy policy;
    private final PatientService patients;
    private final SnapshotHasher snapshots;
    private final ObjectStorage storage;
    private final StorageProperties storageProps;
    private final AccessLog accessLog;

    public SharedHistoryService(JdbcClient jdbc, ClinicalAccessPolicy policy, PatientService patients,
                                SnapshotHasher snapshots, ObjectStorage storage, StorageProperties storageProps,
                                AccessLog accessLog) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.patients = patients;
        this.snapshots = snapshots;
        this.storage = storage;
        this.storageProps = storageProps;
        this.accessLog = accessLog;
    }

    public record SharedEncounter(UUID encounterId, String clinicName, String doctorName, Instant date, int version,
                                  EncounterContent content) {
    }

    public record SharedAllergy(String clinicName, String substance, String reaction, String severity, Instant recordedAt) {
    }

    public record SharedDocument(UUID id, String source, String clinicName, String docType, String title,
                                 LocalDate recordDate, boolean verified, int pages) {
    }

    public record SharedHistory(Set<String> scope, LocalDate fromDate, List<SharedEncounter> encounters,
                                List<SharedAllergy> allergies, List<SharedDocument> documents) {
    }

    public record PageLink(int pageNo, String mimeType, URL url) {
    }

    public record SharedDocumentLinks(SharedDocument document, List<PageLink> pages, Instant linksExpireAt) {
    }

    @Transactional
    public SharedHistory history(ClinicContext ctx, UUID patientId) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        EffectiveAccess access = requireAccess(ctx, profileId);

        List<SharedEncounter> encounters = List.of();
        List<SharedAllergy> allergies = List.of();
        if (access.allows("VISITS")) {
            record EncRow(UUID id, String clinicName, String doctorName, Instant startedAt, int currentVersion, String snapshot) {
            }
            encounters = jdbc.sql("""
                    SELECT e.id, c.name AS clinic_name, s.name AS doctor_name, e.started_at, e.current_version,
                           v.snapshot::text AS snapshot
                      FROM encounter e
                      JOIN clinic c ON c.id = e.clinic_id
                      JOIN staff_user s ON s.id = e.doctor_id
                      JOIN encounter_version v ON v.encounter_id = e.id AND v.version = e.current_version
                     WHERE e.profile_id = :profileId AND e.clinic_id <> :clinicId AND e.status = 'SIGNED'
                       AND (CAST(:fromDate AS date) IS NULL OR e.started_at >= CAST(:fromDate AS date))
                     ORDER BY e.started_at DESC
                     LIMIT 50
                    """)
                    .param("profileId", profileId)
                    .param("clinicId", ctx.clinicId())
                    .param("fromDate", access.fromDate())
                    .query(EncRow.class)
                    .list().stream()
                    .map(r -> new SharedEncounter(r.id(), r.clinicName(), r.doctorName(), r.startedAt(), r.currentVersion(),
                            withoutPrivateNote(snapshots.fromJson(r.snapshot()))))
                    .toList();
            allergies = jdbc.sql("""
                    SELECT c.name AS clinic_name, a.substance, a.reaction, a.severity, a.recorded_at
                      FROM allergy a JOIN clinic c ON c.id = a.clinic_id
                     WHERE a.profile_id = :profileId AND a.clinic_id <> :clinicId AND a.status = 'ACTIVE'
                     ORDER BY a.recorded_at
                    """)
                    .param("profileId", profileId)
                    .param("clinicId", ctx.clinicId())
                    .query(SharedAllergy.class)
                    .list();
        }
        List<SharedDocument> documents = access.allows("DOCUMENTS")
                ? jdbc.sql(SHARED_DOCS + " ORDER BY coalesce(d.record_date, d.created_at::date) DESC LIMIT 100")
                        .param("profileId", profileId)
                        .param("clinicId", ctx.clinicId())
                        .param("fromDate", access.fromDate())
                        .query(SharedDocument.class)
                        .list()
                : List.of();

        accessLog.staffRead(ctx, profileId, "VIEW_SHARED_HISTORY", "patient_profile", String.valueOf(profileId),
                access.primaryGrant());
        return new SharedHistory(access.types(), access.fromDate(), encounters, allergies, documents);
    }

    @Transactional
    public SharedDocumentLinks document(ClinicContext ctx, UUID patientId, UUID documentId) {
        ctx.require(ClinicRole.DOCTOR, ClinicRole.NURSE);
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        EffectiveAccess access = requireAccess(ctx, profileId);
        if (!access.allows("DOCUMENTS")) {
            throw ApiException.forbidden("OUT_OF_SCOPE", "The patient didn't share documents with this clinic.");
        }
        SharedDocument doc = jdbc.sql(SHARED_DOCS + " AND d.id = :id")
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("fromDate", access.fromDate())
                .param("id", documentId)
                .query(SharedDocument.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Shared document"));
        record FileRow(int pageNo, String mimeType, String storageKey) {
        }
        List<PageLink> pages = jdbc.sql("""
                SELECT page_no, mime_type, storage_key FROM document_file WHERE document_id = :id ORDER BY page_no
                """)
                .param("id", documentId)
                .query(FileRow.class)
                .list().stream()
                .map(f -> new PageLink(f.pageNo(), f.mimeType(), storage.presignDownload(f.storageKey())))
                .toList();
        accessLog.staffRead(ctx, profileId, "VIEW_SHARED_DOCUMENT", "medical_document", documentId.toString(),
                access.primaryGrant());
        return new SharedDocumentLinks(doc, pages, Instant.now().plus(storageProps.presignTtl()));
    }

    /** Documents uploaded by other clinics or by the patient. */
    private static final String SHARED_DOCS = """
            SELECT d.id, d.source, c.name AS clinic_name, d.doc_type, d.title, d.record_date, d.verified,
                   (SELECT count(*) FROM document_file f WHERE f.document_id = d.id)::int AS pages
              FROM medical_document d LEFT JOIN clinic c ON c.id = d.clinic_id
             WHERE d.profile_id = :profileId AND d.status = 'READY' AND d.deleted_at IS NULL
               AND (d.clinic_id IS NULL OR d.clinic_id <> :clinicId)
               AND (CAST(:fromDate AS date) IS NULL OR coalesce(d.record_date, d.created_at::date) >= CAST(:fromDate AS date))
            """;

    private EffectiveAccess requireAccess(ClinicContext ctx, long profileId) {
        return policy.effectiveAccess(ctx, profileId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                "NO_RECORD_ACCESS", "Ask the patient to approve access to records from other clinics."));
    }

    private static EncounterContent withoutPrivateNote(SignedSnapshot s) {
        EncounterContent c = s.content();
        return new EncounterContent(c.chiefComplaints(), c.historyOfPresentIllness(), c.pastHistory(), c.examination(),
                c.treatmentGiven(), c.advice(), null, c.vitals(), c.diagnoses(), c.prescription(), c.investigations(),
                c.followUp());
    }
}
