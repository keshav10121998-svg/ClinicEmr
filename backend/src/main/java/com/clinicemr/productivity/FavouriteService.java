package com.clinicemr.productivity;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.clinicemr.clinical.EncounterContent;
import com.clinicemr.clinical.EncounterContent.DiagnosisEntry;
import com.clinicemr.clinical.EncounterContent.InvestigationEntry;
import com.clinicemr.clinical.EncounterContent.RxItem;
import com.clinicemr.productivity.ProductivityDtos.FavouriteRequest;
import com.clinicemr.productivity.ProductivityDtos.FavouriteView;
import com.clinicemr.shared.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A doctor's most used diagnoses, medicines (with their usual dose and frequency) and tests. Learned from every
 * signed visit; the doctor can also pin entries so they stay on top.
 */
@Service
public class FavouriteService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final DoctorGuard doctors;

    public FavouriteService(JdbcClient jdbc, ObjectMapper json, DoctorGuard doctors) {
        this.jdbc = jdbc;
        this.json = json;
        this.doctors = doctors;
    }

    /** Called inside the signing transaction. */
    public void recordUse(long doctorId, EncounterContent content) {
        content.diagnoses().forEach(d -> upsert(doctorId, "DIAGNOSIS", ref(d), d, true, null));
        content.prescription().forEach(r -> upsert(doctorId, "DRUG", ref(r), r, true, null));
        content.investigations().forEach(i -> upsert(doctorId, "INVESTIGATION", ref(i), i, true, null));
    }

    public List<FavouriteView> list(String kind, String q) {
        long doctorId = doctors.currentDoctorId();
        if (kind == null || !List.of("DIAGNOSIS", "DRUG", "INVESTIGATION").contains(kind)) {
            throw ApiException.badRequest("INVALID_KIND", "kind must be DIAGNOSIS, DRUG or INVESTIGATION.");
        }
        String term = q == null || q.isBlank() ? null : q.trim();
        record Row(String kind, String ref, String payload, int useCount, boolean pinned, Instant lastUsedAt) {
        }
        return jdbc.sql("""
                SELECT kind, ref, payload::text AS payload, use_count, pinned, last_used_at FROM doctor_favourite
                 WHERE doctor_id = :doctorId AND kind = :kind
                   AND (CAST(:term AS text) IS NULL
                        OR coalesce(payload->>'display', payload->>'drugDisplay', '') ILIKE '%' || :term || '%'
                        OR coalesce(payload->>'code', '') ILIKE :term || '%')
                 ORDER BY pinned DESC, use_count DESC, last_used_at DESC
                 LIMIT 20
                """)
                .param("doctorId", doctorId)
                .param("kind", kind)
                .param("term", term)
                .query(Row.class)
                .list().stream()
                .map(r -> new FavouriteView(r.kind(), r.ref(), readTree(r.payload()), r.useCount(), r.pinned(), r.lastUsedAt()))
                .toList();
    }

    @Transactional
    public FavouriteView setPinned(FavouriteRequest req) {
        long doctorId = doctors.currentDoctorId();
        Object entry;
        String ref;
        switch (req.kind()) {
            case "DIAGNOSIS" -> {
                entry = require(req.diagnosis(), "diagnosis");
                ref = ref(req.diagnosis());
            }
            case "DRUG" -> {
                entry = require(req.drug(), "drug");
                ref = ref(req.drug());
            }
            default -> {
                entry = require(req.investigation(), "investigation");
                ref = ref(req.investigation());
            }
        }
        upsert(doctorId, req.kind(), ref, entry, false, req.pinned());
        return list(req.kind(), null).stream().filter(f -> f.ref().equals(ref)).findFirst().orElseThrow();
    }

    private void upsert(long doctorId, String kind, String ref, Object entry, boolean countUse, Boolean pinned) {
        jdbc.sql("""
                INSERT INTO doctor_favourite (doctor_id, kind, ref, payload, use_count, pinned)
                VALUES (:doctorId, :kind, :ref, CAST(:payload AS jsonb), :uses, coalesce(CAST(:pinned AS boolean), false))
                ON CONFLICT (doctor_id, kind, ref) DO UPDATE SET
                    payload = EXCLUDED.payload,
                    use_count = doctor_favourite.use_count + :uses,
                    pinned = coalesce(CAST(:pinned AS boolean), doctor_favourite.pinned),
                    last_used_at = CASE WHEN :uses > 0 THEN now() ELSE doctor_favourite.last_used_at END
                """)
                .param("doctorId", doctorId)
                .param("kind", kind)
                .param("ref", ref)
                .param("payload", toJson(entry))
                .param("uses", countUse ? 1 : 0)
                .param("pinned", pinned)
                .update();
    }

    static String ref(DiagnosisEntry d) {
        return d.code() != null && !d.code().isBlank() ? d.code().trim().toUpperCase(Locale.ROOT) : "name:" + norm(d.display());
    }

    static String ref(RxItem r) {
        return r.drugId() != null ? "drug:" + r.drugId() : "name:" + norm(r.drugDisplay());
    }

    static String ref(InvestigationEntry i) {
        return i.code() != null && !i.code().isBlank() ? i.code().trim().toUpperCase(Locale.ROOT) : "name:" + norm(i.display());
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw ApiException.badRequest("MISSING_ENTRY", "Include the " + field + " to pin.");
        }
        return value;
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
