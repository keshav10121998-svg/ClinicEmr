package com.clinicemr.records.storage;

import java.net.URL;
import java.util.Optional;

/**
 * Port for document storage. Downloads use short-lived signed URLs issued after a permission check. Uploads go
 * through the backend ({@link #put}) by default, or straight to storage with a pre-signed PUT URL.
 */
public interface ObjectStorage {

    URL presignUpload(String key, String contentType);

    URL presignDownload(String key);

    void put(String key, String contentType, byte[] bytes);

    /** The object's bytes, if it exists. Used to serve downloads through the backend. */
    Optional<byte[]> read(String key);

    /** Size in bytes if the object exists. */
    Optional<Long> sizeOf(String key);

    void delete(String key);
}
