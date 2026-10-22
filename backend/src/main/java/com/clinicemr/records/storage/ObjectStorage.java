package com.clinicemr.records.storage;

import java.net.URL;
import java.util.Optional;

/**
 * Port for document storage. Files never pass through the application: clients upload and download
 * directly with short-lived pre-signed URLs issued after a permission check.
 */
public interface ObjectStorage {

    URL presignUpload(String key, String contentType);

    URL presignDownload(String key);

    /** Size in bytes if the object exists. */
    Optional<Long> sizeOf(String key);

    void delete(String key);
}
