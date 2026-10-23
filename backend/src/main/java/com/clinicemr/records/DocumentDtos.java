package com.clinicemr.records;

import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record FileSpec(@NotBlank String mimeType, @Positive long sizeBytes) {
    }

    public record StartUploadRequest(
            @NotBlank @Pattern(regexp = "PRESCRIPTION|LAB_REPORT|IMAGING|DISCHARGE_SUMMARY|VACCINATION|BILL|INSURANCE|OTHER")
            String docType,
            @Size(max = 200) String title,
            @PastOrPresent LocalDate recordDate,
            @NotEmpty @Size(max = 20, message = "at most 20 pages per document") List<@Valid FileSpec> files,
            UUID encounterId) {
    }

    /**
     * Where to PUT one page. A relative URL ({@code /api/v1/…}) is this backend and needs the usual Authorization
     * header; an absolute URL is a pre-signed storage URL and needs only the Content-Type.
     */
    public record UploadTarget(int pageNo, String method, String url, String contentType) {
    }

    public record StartUploadResponse(UUID documentId, List<UploadTarget> uploads, Instant urlsExpireAt, String next) {
    }

    /** {@code encounterId} is set when the file was attached to a visit. */
    public record DocumentSummary(UUID id, String docType, String title, LocalDate recordDate, String source,
                                  boolean verified, int pages, Instant createdAt, UUID encounterId) {
    }

    public record PageLink(int pageNo, String mimeType, Long sizeBytes, URL url) {
    }

    public record DocumentDetail(DocumentSummary document, List<PageLink> pages, Instant linksExpireAt) {
    }
}
