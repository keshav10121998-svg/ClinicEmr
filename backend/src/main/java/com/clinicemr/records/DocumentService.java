package com.clinicemr.records;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.clinicemr.patient.PatientService;
import com.clinicemr.records.DocumentDtos.DocumentDetail;
import com.clinicemr.records.DocumentDtos.DocumentSummary;
import com.clinicemr.records.DocumentDtos.FileSpec;
import com.clinicemr.records.DocumentDtos.PageLink;
import com.clinicemr.records.DocumentDtos.StartUploadRequest;
import com.clinicemr.records.DocumentDtos.StartUploadResponse;
import com.clinicemr.records.DocumentDtos.UploadTarget;
import com.clinicemr.records.storage.ObjectStorage;
import com.clinicemr.records.storage.StorageProperties;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documents uploaded by a clinic for one of its patients. Upload is two steps: the app asks for pre-signed
 * URLs, PUTs the files straight to storage, then calls complete. Only the custodian clinic can see these
 * documents for now; sharing across clinics comes with patient-approved access grants.
 */
@Service
public class DocumentService {

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "application/pdf", "pdf",
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/heic", "heic");

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final StorageProperties storageProps;
    private final PatientService patients;
    private final AuditService audit;

    public DocumentService(JdbcClient jdbc, ObjectStorage storage, StorageProperties storageProps,
                           PatientService patients, AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.storageProps = storageProps;
        this.patients = patients;
        this.audit = audit;
    }

    private record FileRow(UUID id, int pageNo, String storageKey, String mimeType, Long sizeBytes) {
    }

    private record DocRow(UUID id, String docType, String title, LocalDate recordDate, String source,
                          boolean verified, String status, int pages, Instant createdAt) {
        DocumentSummary toSummary() {
            return new DocumentSummary(id, docType, title, recordDate, source, verified, pages, createdAt);
        }
    }

    @Transactional
    public StartUploadResponse startUpload(ClinicContext ctx, UUID patientId, StartUploadRequest req) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        for (FileSpec f : req.files()) {
            if (!ALLOWED_TYPES.containsKey(f.mimeType())) {
                throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE",
                        "Upload PDF, JPEG, PNG, WebP or HEIC files. Got: " + f.mimeType());
            }
            if (f.sizeBytes() > storageProps.maxFileBytes()) {
                throw ApiException.badRequest("FILE_TOO_LARGE",
                        "Each file must be at most " + (storageProps.maxFileBytes() / (1024 * 1024)) + " MB.");
            }
        }

        UUID documentId = jdbc.sql("""
                INSERT INTO medical_document (profile_id, source, clinic_id, uploaded_by, doc_type, title, record_date, verified)
                VALUES (:profileId, 'CLINIC', :clinicId, :uploadedBy, :docType, :title, :recordDate, true)
                RETURNING id
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("uploadedBy", "staff:" + ctx.staffId())
                .param("docType", req.docType())
                .param("title", req.title())
                .param("recordDate", req.recordDate())
                .query(UUID.class)
                .single();

        List<UploadTarget> targets = new ArrayList<>();
        int page = 1;
        for (FileSpec f : req.files()) {
            String key = "documents/" + documentId + "/" + page + "." + ALLOWED_TYPES.get(f.mimeType());
            jdbc.sql("""
                    INSERT INTO document_file (document_id, page_no, storage_key, mime_type)
                    VALUES (:documentId, :pageNo, :key, :mimeType)
                    """)
                    .param("documentId", documentId)
                    .param("pageNo", page)
                    .param("key", key)
                    .param("mimeType", f.mimeType())
                    .update();
            targets.add(new UploadTarget(page, "PUT", storage.presignUpload(key, f.mimeType()), f.mimeType()));
            page++;
        }

        audit.staff(ctx.staffId(), ctx.clinicId(), "DOCUMENT_UPLOAD_STARTED", "medical_document",
                documentId.toString(), Map.of("pages", targets.size()));
        return new StartUploadResponse(documentId, targets, Instant.now().plus(storageProps.presignTtl()),
                "PUT each file to its URL with the same Content-Type, then call complete.");
    }

    // Keep the REJECTED status even though the call ends with an error
    @Transactional(noRollbackFor = ApiException.class)
    public DocumentSummary completeUpload(ClinicContext ctx, UUID patientId, UUID documentId) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        DocRow doc = findOwnDocument(ctx, profileId, documentId)
                .orElseThrow(() -> ApiException.notFound("Document"));
        if (!"UPLOADING".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "This upload was already completed.");
        }

        List<FileRow> files = files(documentId);
        for (FileRow f : files) {
            Optional<Long> size = storage.sizeOf(f.storageKey());
            if (size.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_UPLOADED",
                        "Page " + f.pageNo() + " hasn't been uploaded yet.", Map.of("pageNo", f.pageNo()));
            }
            if (size.get() > storageProps.maxFileBytes()) {
                files.forEach(x -> storage.delete(x.storageKey()));
                setStatus(documentId, "REJECTED");
                throw ApiException.badRequest("FILE_TOO_LARGE", "Page " + f.pageNo() + " is larger than allowed.");
            }
            jdbc.sql("UPDATE document_file SET size_bytes = :size WHERE id = :id")
                    .param("size", size.get())
                    .param("id", f.id())
                    .update();
        }

        // TODO: move to SCANNING and let the worker run ClamAV, content-type sniffing and thumbnails
        setStatus(documentId, "READY");
        audit.staff(ctx.staffId(), ctx.clinicId(), "DOCUMENT_UPLOADED", "medical_document", documentId.toString(),
                Map.of("pages", files.size()));
        return findOwnDocument(ctx, profileId, documentId).orElseThrow().toSummary();
    }

    public List<DocumentSummary> list(ClinicContext ctx, UUID patientId) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        return jdbc.sql(SELECT_DOC + " AND d.status = 'READY' ORDER BY d.record_date DESC NULLS LAST, d.created_at DESC")
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .query(DocRow.class)
                .list().stream().map(DocRow::toSummary).toList();
    }

    @Transactional
    public DocumentDetail get(ClinicContext ctx, UUID patientId, UUID documentId) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        DocRow doc = findOwnDocument(ctx, profileId, documentId)
                .filter(d -> "READY".equals(d.status()))
                .orElseThrow(() -> ApiException.notFound("Document"));
        List<PageLink> pages = files(documentId).stream()
                .map(f -> new PageLink(f.pageNo(), f.mimeType(), f.sizeBytes(), storage.presignDownload(f.storageKey())))
                .toList();
        audit.staff(ctx.staffId(), ctx.clinicId(), "DOCUMENT_VIEWED", "medical_document", documentId.toString(), Map.of());
        return new DocumentDetail(doc.toSummary(), pages, Instant.now().plus(storageProps.presignTtl()));
    }

    private static final String SELECT_DOC = """
            SELECT d.id, d.doc_type, d.title, d.record_date, d.source, d.verified, d.status, d.created_at,
                   (SELECT count(*) FROM document_file f WHERE f.document_id = d.id) AS pages
              FROM medical_document d
             WHERE d.profile_id = :profileId AND d.clinic_id = :clinicId AND d.deleted_at IS NULL
            """;

    private Optional<DocRow> findOwnDocument(ClinicContext ctx, long profileId, UUID documentId) {
        return jdbc.sql(SELECT_DOC + " AND d.id = :id")
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("id", documentId)
                .query(DocRow.class)
                .optional();
    }

    private List<FileRow> files(UUID documentId) {
        return jdbc.sql("""
                SELECT id, page_no, storage_key, mime_type, size_bytes FROM document_file
                 WHERE document_id = :documentId ORDER BY page_no
                """)
                .param("documentId", documentId)
                .query(FileRow.class)
                .list();
    }

    private void setStatus(UUID documentId, String status) {
        jdbc.sql("UPDATE medical_document SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", documentId)
                .update();
    }
}
