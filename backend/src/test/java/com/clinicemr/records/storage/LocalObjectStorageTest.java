package com.clinicemr.records.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import com.clinicemr.shared.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {

    @TempDir
    Path dir;

    private LocalObjectStorage storage() throws Exception {
        StorageProperties props = new StorageProperties("local", "proxy", null, null, null, null, null, false,
                Duration.ofMinutes(5), 1024, dir.toString(), "http://localhost:18080", null);
        return new LocalObjectStorage(props, new SecurityProperties("test-secret-test-secret-test-secret-1234", Duration.ofHours(1)));
    }

    @Test
    void storesAndServesThroughSignedLinks() throws Exception {
        LocalObjectStorage s = storage();
        s.put("documents/abc/1.pdf", "application/pdf", "%PDF-1.7".getBytes());
        assertThat(s.sizeOf("documents/abc/1.pdf")).contains(8L);

        Map<String, String> q = query(s.presignDownload("documents/abc/1.pdf").toURI());
        long exp = Long.parseLong(q.get("exp"));
        assertThat(s.verifiedFile(q.get("key"), exp, q.get("sig"))).isPresent();

        // Tampered key, signature or expiry, or an expired link, are refused
        assertThat(s.verifiedFile("documents/abc/2.pdf", exp, q.get("sig"))).isEmpty();
        assertThat(s.verifiedFile(q.get("key"), exp + 60, q.get("sig"))).isEmpty();
        assertThat(s.verifiedFile(q.get("key"), exp, "00" + q.get("sig").substring(2))).isEmpty();
        assertThat(s.verifiedFile(q.get("key"), 1L, q.get("sig"))).isEmpty();

        s.delete("documents/abc/1.pdf");
        assertThat(s.sizeOf("documents/abc/1.pdf")).isEmpty();
    }

    @Test
    void keysCannotEscapeTheStorageFolder() throws Exception {
        LocalObjectStorage s = storage();
        assertThatThrownBy(() -> s.put("../outside.txt", "text/plain", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.resolve("documents/../../x")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> query(URI uri) {
        return java.util.Arrays.stream(uri.getRawQuery().split("&"))
                .map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8)));
    }
}
