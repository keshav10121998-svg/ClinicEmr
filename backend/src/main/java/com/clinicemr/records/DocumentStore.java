package com.clinicemr.records;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.clinicemr.records.DocumentDtos.DocumentSummary;
import com.clinicemr.records.DocumentDtos.FileSpec;
import com.clinicemr.records.DocumentDtos.PageLink;
import com.clinicemr.records.DocumentDtos.StartUploadRequest;
import com.clinicemr.records.DocumentDtos.StartUploadResponse;
import com.clinicemr.records.DocumentDtos.UploadTarget;
import com.clinicemr.records.storage.ObjectStorage;
import com.clinicemr.records.storage.StorageProperties;
import com.clinicemr.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Upload mechanics shared by clinic and patient uploads: validation, storage keys, pre-signed URLs, completion and
 * deletion. Callers decide who may touch which document before calling in.
 */
@Component
public class DocumentStore {

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "application/pdf", "pdf",
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/heic", "heic");

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final StorageProperties props;

    public DocumentStore(JdbcClient jdbc, ObjectStorage storage, StorageProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.props = props;
    }

    /** Who a new document comes from. {@code clinicId} is null for patient uploads. */
    public record Origin(String source, Long clinicId, String uploadedBy, boolean verified) {
    }

    record FileRow(UUID id, int pageNo, String storageKey, String mimeType, Long sizeBytes) {
    }

    public record DocRow(UUID id, long profileId, Long clinicId, String uploadedBy, String docType, String title,
                         LocalDate recordDate, String source, boolean verified, String status, int pages, Instant createdAt) {
        public DocumentSummary toSummary() {
            return new DocumentSummary(id, docType, title, recordDate, source, verified, pages, createdAt);
        }
    }

    public static final String SELECT_DOC = """
            SELECT d.id, d.profile_id, d.clinic_id, d.uploaded_by, d.doc_type, d.title, d.record_date, d.source,
                   d.verified, d.status, d.created_at,
                   (SELECT count(*) FROM document_file f WHERE f.document_id = d.id)::int AS pages
              FROM medical_document d
             WHERE d.deleted_at IS NULL
            """;

    public Optional<DocRow> find(UUID documentId) {
        return jdbc.sql(SELECT_DOC + " AND d.id = :id")
                .param("id", documentId)
                .query(DocRow.class)
                .optional();
    }

    public StartUploadResponse start(long profileId, Origin origin, StartUploadRequest req) {
        for (FileSpec f : req.files()) {
            if (!ALLOWED_TYPES.containsKey(f.mimeType())) {
                throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE",
                        "Upload PDF, JPEG, PNG, WebP or HEIC files. Got: " + f.mimeType());
            }
            if (f.sizeBytes() > props.maxFileBytes()) {
                throw ApiException.badRequest("FILE_TOO_LARGE",
                        "Each file must be at most " + (props.maxFileBytes() / (1024 * 1024)) + " MB.");
            }
        }

        UUID documentId = jdbc.sql("""
                INSERT INTO medical_document (profile_id, source, clinic_id, uploaded_by, doc_type, title, record_date, verified)
                VALUES (:profileId, :source, :clinicId, :uploadedBy, :docType, :title, :recordDate, :verified)
                RETURNING id
                """)
                .param("profileId", profileId)
                .param("source", origin.source())
                .param("clinicId", origin.clinicId())
                .param("uploadedBy", origin.uploadedBy())
                .param("docType", req.docType())
                .param("title", req.title())
                .param("recordDate", req.recordDate())
                .param("verified", origin.verified())
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
        return new StartUploadResponse(documentId, targets, Instant.now().plus(props.presignTtl()),
                "PUT each file to its URL with the same Content-Type, then call complete.");
    }

    /**
     * Checks every page arrived and is within limits, then marks the document ready. Callers must not roll back on
     * {@link ApiException}, so a rejected upload stays rejected.
     */
    public DocumentSummary complete(DocRow doc) {
        if (!"UPLOADING".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "This upload was already completed.");
        }
        List<FileRow> files = files(doc.id());
        for (FileRow f : files) {
            Optional<Long> size = storage.sizeOf(f.storageKey());
            if (size.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_UPLOADED",
                        "Page " + f.pageNo() + " hasn't been uploaded yet.", Map.of("pageNo", f.pageNo()));
            }
            if (size.get() > props.maxFileBytes()) {
                files.forEach(x -> storage.delete(x.storageKey()));
                setStatus(doc.id(), "REJECTED");
                throw ApiException.badRequest("FILE_TOO_LARGE", "Page " + f.pageNo() + " is larger than allowed.");
            }
            jdbc.sql("UPDATE document_file SET size_bytes = :size WHERE id = :id")
                    .param("size", size.get())
                    .param("id", f.id())
                    .update();
        }
        // TODO: move to SCANNING and let the worker run ClamAV, content-type sniffing and thumbnails
        setStatus(doc.id(), "READY");
        return find(doc.id()).orElseThrow().toSummary();
    }

    public List<PageLink> pageLinks(UUID documentId) {
        return files(documentId).stream()
                .map(f -> new PageLink(f.pageNo(), f.mimeType(), f.sizeBytes(), storage.presignDownload(f.storageKey())))
                .toList();
    }

    /** Removes the files from storage and marks the document deleted. */
    public void delete(UUID documentId) {
        files(documentId).forEach(f -> storage.delete(f.storageKey()));
        jdbc.sql("UPDATE medical_document SET status = 'DELETED', deleted_at = now() WHERE id = :id")
                .param("id", documentId)
                .update();
    }

    public Instant linksExpireAt() {
        return Instant.now().plus(props.presignTtl());
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
