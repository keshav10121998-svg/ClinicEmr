package com.clinicemr.records;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.patient.PatientService;
import com.clinicemr.records.DocumentDtos.DocumentDetail;
import com.clinicemr.records.DocumentDtos.DocumentSummary;
import com.clinicemr.records.DocumentDtos.StartUploadRequest;
import com.clinicemr.records.DocumentDtos.StartUploadResponse;
import com.clinicemr.records.DocumentStore.DocRow;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documents a clinic uploads for one of its patients. The clinic sees its own uploads here; documents from other
 * clinics and from the patient are available through shared history once the patient approves.
 */
@Service
public class DocumentService {

    private final JdbcClient jdbc;
    private final DocumentStore store;
    private final PatientService patients;
    private final AuditService audit;

    public DocumentService(JdbcClient jdbc, DocumentStore store, PatientService patients, AuditService audit) {
        this.jdbc = jdbc;
        this.store = store;
        this.patients = patients;
        this.audit = audit;
    }

    @Transactional
    public StartUploadResponse startUpload(ClinicContext ctx, UUID patientId, StartUploadRequest req) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        StartUploadResponse res = store.start(profileId,
                new DocumentStore.Origin("CLINIC", ctx.clinicId(), "staff:" + ctx.staffId(), true), req);
        audit.staff(ctx.staffId(), ctx.clinicId(), "DOCUMENT_UPLOAD_STARTED", "medical_document",
                res.documentId().toString(), Map.of("pages", res.uploads().size()));
        return res;
    }

    // Keep the REJECTED status even though the call ends with an error
    @Transactional(noRollbackFor = ApiException.class)
    public DocumentSummary completeUpload(ClinicContext ctx, UUID patientId, UUID documentId) {
        DocRow doc = ownDocument(ctx, patientId, documentId);
        DocumentSummary summary = store.complete(doc);
        audit.staff(ctx.staffId(), ctx.clinicId(), "DOCUMENT_UPLOADED", "medical_document", documentId.toString(),
                Map.of("pages", summary.pages()));
        return summary;
    }

    public List<DocumentSummary> list(ClinicContext ctx, UUID patientId) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        return jdbc.sql(DocumentStore.SELECT_DOC + """
                 AND d.profile_id = :profileId AND d.clinic_id = :clinicId AND d.status = 'READY'
                 ORDER BY d.record_date DESC NULLS LAST, d.created_at DESC
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .query(DocRow.class)
                .list().stream().map(DocRow::toSummary).toList();
    }

    @Transactional
    public DocumentDetail get(ClinicContext ctx, UUID patientId, UUID documentId) {
        DocRow doc = ownDocument(ctx, patientId, documentId);
        if (!"READY".equals(doc.status())) {
            throw ApiException.notFound("Document");
        }
        audit.staff(ctx.staffId(), ctx.clinicId(), "DOCUMENT_VIEWED", "medical_document", documentId.toString(), Map.of());
        return new DocumentDetail(doc.toSummary(), store.pageLinks(documentId), store.linksExpireAt());
    }

    /** A document this clinic uploaded for this patient, or 404. */
    private DocRow ownDocument(ClinicContext ctx, UUID patientId, UUID documentId) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        return store.find(documentId)
                .filter(d -> d.profileId() == profileId && d.clinicId() != null && d.clinicId() == ctx.clinicId())
                .orElseThrow(() -> ApiException.notFound("Document"));
    }
}
