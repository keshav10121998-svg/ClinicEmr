package com.clinicemr.records.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.clinicemr.shared.security.SecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Documents on the server's own disk, for local development without MinIO or a cloud bucket. Downloads are served by
 * {@link FileLinkController} through links signed with an HMAC and an expiry, like pre-signed URLs.
 * Not for production: no replication, and files live with the app server.
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local")
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;
    private final SignedFileLinks links;

    public LocalObjectStorage(StorageProperties props, SecurityProperties security) throws IOException {
        this.root = Path.of(props.localDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        this.links = new SignedFileLinks(props, security);
    }

    @Override
    public URL presignUpload(String key, String contentType) {
        throw new UnsupportedOperationException("Local storage takes uploads through the backend only");
    }

    @Override
    public URL presignDownload(String key) {
        return links.create(key);
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
    public Optional<byte[]> read(String key) {
        Path file = resolve(key);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
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
        if (!links.valid(key, expires, signature)) {
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
}
