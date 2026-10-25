package com.clinicemr.records.storage;

import java.util.Locale;
import java.util.Map;

import com.clinicemr.shared.security.SecurityProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves files through the signed, expiring links from {@link SignedFileLinks} (the backend's stand-in for
 * pre-signed URLs). The permission check happened when the link was issued; here only the signature is checked.
 * Answers 404 when downloads don't go through the backend, or the link is invalid or expired.
 */
@RestController
public class FileLinkController {

    private static final Map<String, MediaType> TYPES = Map.of(
            "pdf", MediaType.APPLICATION_PDF,
            "jpg", MediaType.IMAGE_JPEG,
            "png", MediaType.IMAGE_PNG,
            "webp", MediaType.parseMediaType("image/webp"),
            "heic", MediaType.parseMediaType("image/heic"));

    private final ObjectProvider<ObjectStorage> storage;
    private final StorageProperties props;
    private final SignedFileLinks links;

    public FileLinkController(ObjectProvider<ObjectStorage> storage, StorageProperties props, SecurityProperties security) {
        this.storage = storage;
        this.props = props;
        this.links = new SignedFileLinks(props, security);
    }

    @GetMapping(SignedFileLinks.PATH)
    public ResponseEntity<byte[]> download(@RequestParam String key, @RequestParam long exp, @RequestParam String sig) {
        ObjectStorage store = storage.getIfAvailable();
        if (store == null || !props.proxyDownloads() || !links.valid(key, exp, sig)) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes;
        try {
            bytes = store.read(key).orElse(null);
        } catch (IllegalArgumentException e) {
            bytes = null;
        }
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        String name = key.substring(key.lastIndexOf('/') + 1);
        MediaType type = TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT),
                MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(bytes);
    }
}
