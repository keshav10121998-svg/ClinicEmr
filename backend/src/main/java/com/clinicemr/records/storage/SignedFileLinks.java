package com.clinicemr.records.storage;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.clinicemr.shared.security.SecurityProperties;

/**
 * Short-lived download links served by this backend ({@link FileLinkController}), signed with an HMAC and an expiry
 * like pre-signed URLs. Used by local storage, and by S3 storage when downloads go through the backend (for stores
 * whose own pre-signed URLs can't be relied on).
 */
public class SignedFileLinks {

    static final String PATH = "/api/v1/storage/file";

    private final StorageProperties props;
    private final SecretKeySpec key;

    public SignedFileLinks(StorageProperties props, SecurityProperties security) {
        this.props = props;
        this.key = new SecretKeySpec(("files:" + security.jwtSecret()).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public URL create(String storageKey) {
        long expires = Instant.now().plus(props.presignTtl()).getEpochSecond();
        String url = props.localBaseUrl() + PATH + "?key=" + URLEncoder.encode(storageKey, StandardCharsets.UTF_8)
                + "&exp=" + expires + "&sig=" + sign(storageKey, expires);
        try {
            return URI.create(url).toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True if the link is unmodified and not yet expired. */
    public boolean valid(String storageKey, long expires, String signature) {
        if (storageKey == null || signature == null || Instant.now().getEpochSecond() > expires) {
            return false;
        }
        return MessageDigest.isEqual(sign(storageKey, expires).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String storageKey, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((storageKey + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
