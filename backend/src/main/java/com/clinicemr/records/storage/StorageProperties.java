package com.clinicemr.records.storage;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Any S3-compatible object store. Locally MinIO; in the cloud Oracle Object Storage's S3 compatibility API
 * (endpoint {@code https://<namespace>.compat.objectstorage.<region>.oraclecloud.com}, path-style access).
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        URI endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        Duration presignTtl,
        long maxFileBytes) {

    public StorageProperties {
        if (presignTtl == null) {
            presignTtl = Duration.ofMinutes(5);
        }
        if (maxFileBytes <= 0) {
            maxFileBytes = 20L * 1024 * 1024;
        }
    }
}
