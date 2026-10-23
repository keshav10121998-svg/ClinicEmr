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

    /**
     * Who a new document comes from. {@code clinicId} is null for patient uploads; {@code encounterId} links a
     * clinic's file to the visit it was attached in (callers check the visit belongs to them).
     */
    public record Origin(String source, Long clinicId, String uploadedBy, boolean verified, UUID encounterId) {
    }

    record FileRow(UUID id, int pageNo, String storageKey, String mimeType, Long sizeBytes) {
    }

    public record DocRow(UUID id, long profileId, Long clinicId, String uploadedBy, String docType, String title,
                         LocalDate recordDate, String source, boolean verified, String status, int pages, Instant createdAt,
                         UUID encounterId) {
        public DocumentSummary toSummary() {
            return new DocumentSummary(id, docType, title, recordDate, source, verified, pages, createdAt, encounterId);
        }
    }

    public static final String SELECT_DOC = """
            SELECT d.id, d.profile_id, d.clinic_id, d.uploaded_by, d.doc_type, d.title, d.record_date, d.source,
                   d.verified, d.status, d.created_at, d.encounter_id,
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

    /**
     * @param pageUploadPath where pages are PUT when uploads go through the backend, e.g.
     *                       {@code (docId, page) -> "/api/v1/…/documents/" + docId + "/pages/" + page}
     */
    public StartUploadResponse start(long profileId, Origin origin, StartUploadRequest req,
                                     java.util.function.BiFunction<UUID, Integer, String> pageUploadPath) {
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
                INSERT INTO medical_document (profile_id, source, clinic_id, uploaded_by, doc_type, title, record_date,
                                              verified, encounter_id)
                VALUES (:profileId, :source, :clinicId, :uploadedBy, :docType, :title, :recordDate, :verified, :encounterId)
                RETURNING id
                """)
                .param("encounterId", origin.encounterId())
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
            String url = props.proxyUploads()
                    ? pageUploadPath.apply(documentId, page)
                    : storage.presignUpload(key, f.mimeType()).toString();
            targets.add(new UploadTarget(page, "PUT", url, f.mimeType()));
            page++;
        }
        return new StartUploadResponse(documentId, targets, Instant.now().plus(props.presignTtl()),
                "PUT each file to its URL with the same Content-Type, then call complete.");
    }

    /**
     * Stores one page sent through the backend after checking its size and that its content really is the declared
     * type. Callers check who may upload to the document first.
     */
    public void storePage(DocRow doc, int pageNo, String contentType, java.io.InputStream body) {
        if (!"UPLOADING".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "This upload was already completed.");
        }
        FileRow file = files(doc.id()).stream().filter(f -> f.pageNo() == pageNo).findFirst()
                .orElseThrow(() -> ApiException.notFound("Page " + pageNo));
        String declared = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!file.mimeType().equals(declared)) {
            throw ApiException.badRequest("CONTENT_TYPE_MISMATCH",
                    "Page " + pageNo + " was announced as " + file.mimeType() + " but sent as " + declared + ".");
        }
        byte[] bytes = readLimited(body, props.maxFileBytes());
        if (bytes.length == 0) {
            throw ApiException.badRequest("EMPTY_FILE", "Page " + pageNo + " is empty.");
        }
        if (!FileSignatures.matches(file.mimeType(), bytes)) {
            throw ApiException.badRequest("CONTENT_NOT_ALLOWED",
                    "Page " + pageNo + " isn't a real " + ALLOWED_TYPES.get(file.mimeType()).toUpperCase(java.util.Locale.ROOT) + " file.");
        }
        storage.put(file.storageKey(), file.mimeType(), bytes);
    }

    private static byte[] readLimited(java.io.InputStream in, long max) {
        try (in) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
            if (bytes.length > max) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                        "Each file must be at most " + (max / (1024 * 1024)) + " MB.");
            }
            return bytes;
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("UPLOAD_INTERRUPTED", "The upload was interrupted. Try again.");
        }
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
