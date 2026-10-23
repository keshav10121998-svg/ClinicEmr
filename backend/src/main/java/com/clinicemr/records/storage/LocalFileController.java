package com.clinicemr.records.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

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
 * Serves files for {@link LocalObjectStorage} through signed, expiring links (the local stand-in for pre-signed
 * URLs). Answers 404 whenever local storage isn't in use or the link is invalid or expired.
 */
@RestController
public class LocalFileController {

    private static final Map<String, MediaType> TYPES = Map.of(
            "pdf", MediaType.APPLICATION_PDF,
            "jpg", MediaType.IMAGE_JPEG,
            "png", MediaType.IMAGE_PNG,
            "webp", MediaType.parseMediaType("image/webp"),
            "heic", MediaType.parseMediaType("image/heic"));

    private final ObjectProvider<LocalObjectStorage> storage;

    public LocalFileController(ObjectProvider<LocalObjectStorage> storage) {
        this.storage = storage;
    }

    @GetMapping("/api/v1/storage/local")
    public ResponseEntity<byte[]> download(@RequestParam String key, @RequestParam long exp, @RequestParam String sig)
            throws IOException {
        LocalObjectStorage local = storage.getIfAvailable();
        if (local == null) {
            return ResponseEntity.notFound().build();
        }
        Path file;
        try {
            file = local.verifiedFile(key, exp, sig).orElse(null);
        } catch (IllegalArgumentException e) {
            file = null;
        }
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        String name = file.getFileName().toString();
        MediaType type = TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT),
                MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(Files.readAllBytes(file));
    }
}
