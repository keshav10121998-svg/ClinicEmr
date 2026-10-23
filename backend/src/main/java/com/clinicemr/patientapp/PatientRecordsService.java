package com.clinicemr.patientapp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.clinical.EncounterContent;
import com.clinicemr.clinical.SignedSnapshot;
import com.clinicemr.clinical.SnapshotHasher;
import com.clinicemr.prescription.PrescriptionPdfRenderer;
import com.clinicemr.records.DocumentDtos.DocumentDetail;
import com.clinicemr.records.DocumentDtos.StartUploadRequest;
import com.clinicemr.records.DocumentDtos.StartUploadResponse;
import com.clinicemr.records.DocumentStore;
import com.clinicemr.records.DocumentStore.DocRow;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.PatientContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A patient's own records from every clinic, for each profile the account manages: signed visits, prescriptions
 * and documents. Patients can upload old records; those are marked as self-uploaded and are never "verified".
 */
@Service
public class PatientRecordsService {

    private final JdbcClient jdbc;
    private final SnapshotHasher snapshots;
    private final PrescriptionPdfRenderer renderer;
    private final DocumentStore store;
    private final AuditService audit;

    public PatientRecordsService(JdbcClient jdbc, SnapshotHasher snapshots, PrescriptionPdfRenderer renderer,
                                 DocumentStore store, AuditService audit) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.renderer = renderer;
        this.store = store;
        this.audit = audit;
    }

    public record VisitItem(UUID encounterId, String clinicName, String clinicCity, String doctorName, Instant date,
                            int version, String diagnoses, int medicines) {
    }

    public record VisitDetail(UUID encounterId, String clinicName, String doctorName, String doctorQualifications,
                              Instant date, int version, boolean amended, EncounterContent content) {
    }

    public record PatientDocument(UUID id, String docType, String title, LocalDate recordDate, String source,
                                  String clinicName, boolean verified, int pages, Instant createdAt, boolean canDelete) {
    }

    public List<VisitItem> visits(PatientContext p, UUID profileId) {
        long id = requireProfile(p, profileId);
        return jdbc.sql("""
                SELECT e.id AS encounter_id, c.name AS clinic_name, c.city AS clinic_city, s.name AS doctor_name,
                       e.started_at AS date, e.current_version AS version,
                       coalesce((SELECT string_agg(d.display, '; ' ORDER BY d.line_no) FROM diagnosis d
                                  WHERE d.encounter_id = e.id), '') AS diagnoses,
                       (SELECT count(*) FROM prescription_item pi JOIN prescription rx ON rx.id = pi.prescription_id
                         WHERE rx.encounter_id = e.id)::int AS medicines
                  FROM encounter e JOIN clinic c ON c.id = e.clinic_id JOIN staff_user s ON s.id = e.doctor_id
                 WHERE e.profile_id = :profileId AND e.status = 'SIGNED'
                 ORDER BY e.started_at DESC
                 LIMIT 200
                """)
                .param("profileId", id)
                .query(VisitItem.class)
                .list();
    }

    /** A signed visit as the doctor signed it, without the doctor's private note. */
    public VisitDetail visit(PatientContext p, UUID profileId, UUID encounterId) {
        SignedSnapshot s = latestSnapshot(requireProfile(p, profileId), encounterId);
        EncounterContent c = s.content();
        EncounterContent shown = new EncounterContent(c.chiefComplaints(), c.historyOfPresentIllness(), c.pastHistory(),
                c.examination(), c.treatmentGiven(), c.advice(), null, c.vitals(), c.diagnoses(), c.prescription(),
                c.investigations(), c.followUp());
        return new VisitDetail(s.encounterId(), s.clinic().name(), s.doctor().name(), s.doctor().qualifications(),
                s.startedAt(), s.version(), s.version() > 1, shown);
    }

    public PrescriptionPdf prescriptionPdf(PatientContext p, UUID profileId, UUID encounterId) {
        SignedSnapshot s = latestSnapshot(requireProfile(p, profileId), encounterId);
        return new PrescriptionPdf(renderer.render(s, PrescriptionPdfRenderer.Paper.A4),
                "prescription-" + s.startedAt().toString().substring(0, 10) + "-v" + s.version() + ".pdf");
    }

    public record PrescriptionPdf(byte[] bytes, String filename) {
    }

    public List<PatientDocument> documents(PatientContext p, UUID profileId) {
        long id = requireProfile(p, profileId);
        record Row(UUID id, String docType, String title, LocalDate recordDate, String source, String clinicName,
                   boolean verified, int pages, Instant createdAt) {
        }
        return jdbc.sql("""
                SELECT d.id, d.doc_type, d.title, d.record_date, d.source, c.name AS clinic_name, d.verified,
                       (SELECT count(*) FROM document_file f WHERE f.document_id = d.id)::int AS pages, d.created_at
                  FROM medical_document d LEFT JOIN clinic c ON c.id = d.clinic_id
                 WHERE d.profile_id = :profileId AND d.status = 'READY' AND d.deleted_at IS NULL
                 ORDER BY coalesce(d.record_date, d.created_at::date) DESC, d.created_at DESC
                """)
                .param("profileId", id)
                .query(Row.class)
                .list().stream()
                .map(r -> new PatientDocument(r.id(), r.docType(), r.title(), r.recordDate(), r.source(), r.clinicName(),
                        r.verified(), r.pages(), r.createdAt(), "PATIENT".equals(r.source())))
                .toList();
    }

    @Transactional
    public StartUploadResponse startUpload(PatientContext p, UUID profileId, StartUploadRequest req) {
        long id = requireProfile(p, profileId);
        StartUploadResponse res = store.start(id, new DocumentStore.Origin("PATIENT", null, "account:" + p.accountId(), false), req,
                (docId, page) -> "/api/v1/patient/profiles/" + profileId + "/documents/" + docId + "/pages/" + page);
        audit.record("PATIENT", "account:" + p.accountId(), null, "DOCUMENT_UPLOAD_STARTED", "medical_document",
                res.documentId().toString(), Map.of("pages", res.uploads().size()));
        return res;
    }

    public void uploadPage(PatientContext p, UUID profileId, UUID documentId, int pageNo, String contentType,
                           java.io.InputStream body) {
        store.storePage(ownUpload(p, profileId, documentId), pageNo, contentType, body);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public PatientDocument completeUpload(PatientContext p, UUID profileId, UUID documentId) {
        DocRow doc = ownUpload(p, profileId, documentId);
        store.complete(doc);
        audit.record("PATIENT", "account:" + p.accountId(), null, "DOCUMENT_UPLOADED", "medical_document",
                documentId.toString(), Map.of());
        return documents(p, profileId).stream().filter(d -> d.id().equals(documentId)).findFirst().orElseThrow();
    }

    public DocumentDetail document(PatientContext p, UUID profileId, UUID documentId) {
        long id = requireProfile(p, profileId);
        DocRow doc = store.find(documentId)
                .filter(d -> d.profileId() == id && "READY".equals(d.status()))
                .orElseThrow(() -> ApiException.notFound("Document"));
        return new DocumentDetail(doc.toSummary(), store.pageLinks(documentId), store.linksExpireAt());
    }

    /** Patients can delete what they uploaded. Documents from clinics are part of the clinic's records and stay. */
    @Transactional
    public void deleteUpload(PatientContext p, UUID profileId, UUID documentId) {
        long id = requireProfile(p, profileId);
        DocRow doc = store.find(documentId)
                .filter(d -> d.profileId() == id)
                .orElseThrow(() -> ApiException.notFound("Document"));
        if (!"PATIENT".equals(doc.source())) {
            throw ApiException.forbidden("CLINIC_DOCUMENT", "Documents from a clinic can't be deleted from the app.");
        }
        store.delete(documentId);
        audit.record("PATIENT", "account:" + p.accountId(), null, "DOCUMENT_DELETED", "medical_document",
                documentId.toString(), Map.of());
    }

    private DocRow ownUpload(PatientContext p, UUID profileId, UUID documentId) {
        long id = requireProfile(p, profileId);
        return store.find(documentId)
                .filter(d -> d.profileId() == id && "PATIENT".equals(d.source()))
                .orElseThrow(() -> ApiException.notFound("Document"));
    }

    private SignedSnapshot latestSnapshot(long profileId, UUID encounterId) {
        String json = jdbc.sql("""
                SELECT v.snapshot::text FROM encounter e
                  JOIN encounter_version v ON v.encounter_id = e.id AND v.version = e.current_version
                 WHERE e.id = :id AND e.profile_id = :profileId AND e.status = 'SIGNED'
                """)
                .param("id", encounterId)
                .param("profileId", profileId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Visit"));
        return snapshots.fromJson(json);
    }

    /** Internal id of a profile this account manages, or 404 so other people's profiles can't be probed. */
    long requireProfile(PatientContext p, UUID profileId) {
        return jdbc.sql("""
                SELECT pp.id FROM profile_access pa JOIN patient_profile pp ON pp.id = pa.profile_id
                 WHERE pa.account_id = :accountId AND pp.public_id = :profileId
                   AND pp.deleted_at IS NULL AND pp.merged_into_id IS NULL
                """)
                .param("accountId", p.accountId())
                .param("profileId", profileId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Profile was not found."));
    }
}
