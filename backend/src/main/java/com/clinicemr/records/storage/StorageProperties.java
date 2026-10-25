package com.clinicemr.records.storage;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where documents are stored.
 * <ul>
 *   <li>{@code type = s3}: any S3-compatible store. Locally MinIO; in the cloud Oracle Object Storage's S3
 *       compatibility API ({@code https://<namespace>.compat.objectstorage.<region>.oraclecloud.com}, path-style).</li>
 *   <li>{@code type = local}: files on the server's disk, for development without any storage service.</li>
 * </ul>
 * {@code uploadMode = proxy} (default) sends uploads through the backend, which checks each file's real type before
 * storing it and needs no CORS setup on the bucket. {@code direct} hands the browser a pre-signed PUT URL instead
 * (S3 only; the bucket must allow the front-end's origin).
 * {@code downloadMode = proxy} serves downloads through this backend with signed links ({@code localBaseUrl} is then
 * the app's public address) for stores whose pre-signed URLs aren't usable; the default uses the store's own.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String type,
        String uploadMode,
        URI endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        Duration presignTtl,
        long maxFileBytes,
        String localDir,
        String localBaseUrl,
        String downloadMode) {

    public StorageProperties {
        if (type == null || type.isBlank()) {
            type = "s3";
        }
        if (uploadMode == null || uploadMode.isBlank()) {
            uploadMode = "proxy";
        }
        if (presignTtl == null) {
            presignTtl = Duration.ofMinutes(5);
        }
        if (maxFileBytes <= 0) {
            maxFileBytes = 20L * 1024 * 1024;
        }
        if (localDir == null || localDir.isBlank()) {
            localDir = "target/dev-storage";
        }
        if (localBaseUrl == null || localBaseUrl.isBlank()) {
            localBaseUrl = "http://localhost:18080";
        }
    }

    /** Downloads go through this backend's signed links instead of the store's own pre-signed URLs. */
    public boolean proxyDownloads() {
        return "proxy".equalsIgnoreCase(downloadMode) || "local".equalsIgnoreCase(type);
    }

    public boolean proxyUploads() {
        return !"direct".equalsIgnoreCase(uploadMode) || "local".equalsIgnoreCase(type);
    }
}
