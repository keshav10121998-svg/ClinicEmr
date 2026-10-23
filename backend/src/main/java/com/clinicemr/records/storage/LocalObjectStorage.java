package com.clinicemr.records.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.clinicemr.shared.security.SecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Documents on the server's own disk, for local development without MinIO or a cloud bucket. Downloads are served by
 * {@code LocalFileController} through links signed with an HMAC and an expiry, like pre-signed URLs.
 * Not for production: no replication, and files live with the app server.
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local")
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;
    private final StorageProperties props;
    private final SecretKeySpec signingKey;

    public LocalObjectStorage(StorageProperties props, SecurityProperties security) throws IOException {
        this.props = props;
        this.root = Path.of(props.localDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        this.signingKey = new SecretKeySpec(("files:" + security.jwtSecret()).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public URL presignUpload(String key, String contentType) {
        throw new UnsupportedOperationException("Local storage takes uploads through the backend only");
    }

    @Override
    public URL presignDownload(String key) {
        long expires = Instant.now().plus(props.presignTtl()).getEpochSecond();
        String url = props.localBaseUrl() + "/api/v1/storage/local?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&exp=" + expires + "&sig=" + sign(key, expires);
        try {
            return URI.create(url).toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void put(String key, String contentType, byte[] bytes) {
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Long> sizeOf(String key) {
        Path file = resolve(key);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.size(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file behind a signed download link, if the signature is valid and not expired. */
    public Optional<Path> verifiedFile(String key, long expires, String signature) {
        if (key == null || signature == null || Instant.now().getEpochSecond() > expires) {
            return Optional.empty();
        }
        boolean valid = MessageDigest.isEqual(sign(key, expires).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            return Optional.empty();
        }
        Path file = resolve(key);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** Maps a storage key to a path under the root; keys that would escape it are rejected. */
    Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return file;
    }

    private String sign(String key, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return HexFormat.of().formatHex(mac.doFinal((key + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
