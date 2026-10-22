package com.clinicemr.clinical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * Serialises snapshots to a canonical JSON form (sorted keys) and hashes them, so a stored signed visit can be
 * checked for tampering by re-hashing it.
 */
@Component
public class SnapshotHasher {

    private final ObjectMapper canonical;

    public SnapshotHasher() {
        this.canonical = JsonMapper.builder()
                .findAndAddModules()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                .build();
    }

    public String toCanonicalJson(SignedSnapshot snapshot) {
        try {
            return canonical.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Snapshot is not serialisable", e);
        }
    }

    public SignedSnapshot fromJson(String json) {
        try {
            return canonical.readValue(json, SignedSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored snapshot can't be read", e);
        }
    }

    public String hash(SignedSnapshot snapshot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(toCanonicalJson(snapshot).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
