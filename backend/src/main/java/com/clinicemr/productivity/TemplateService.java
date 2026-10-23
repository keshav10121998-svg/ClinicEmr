package com.clinicemr.productivity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.clinicemr.clinical.EncounterContent;
import com.clinicemr.productivity.ProductivityDtos.SaveTemplateRequest;
import com.clinicemr.productivity.ProductivityDtos.TemplateContent;
import com.clinicemr.productivity.ProductivityDtos.TemplateView;
import com.clinicemr.shared.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final DoctorGuard doctors;

    public TemplateService(JdbcClient jdbc, ObjectMapper json, DoctorGuard doctors) {
        this.jdbc = jdbc;
        this.json = json;
        this.doctors = doctors;
    }

    private record Row(UUID id, String name, String content, int useCount, Instant updatedAt) {
    }

    public List<TemplateView> list() {
        long doctorId = doctors.currentDoctorId();
        return jdbc.sql("""
                SELECT id, name, content::text AS content, use_count, updated_at FROM doctor_template
                 WHERE doctor_id = :doctorId ORDER BY use_count DESC, lower(name)
                """)
                .param("doctorId", doctorId)
                .query(Row.class)
                .list().stream().map(this::toView).toList();
    }

    @Transactional
    public TemplateView create(SaveTemplateRequest req) {
        long doctorId = doctors.currentDoctorId();
        try {
            UUID id = jdbc.sql("""
                    INSERT INTO doctor_template (doctor_id, name, content) VALUES (:doctorId, :name, CAST(:content AS jsonb))
                    RETURNING id
                    """)
                    .param("doctorId", doctorId)
                    .param("name", req.name().trim())
                    .param("content", toJson(req.content()))
                    .query(UUID.class)
                    .single();
            return get(doctorId, id);
        } catch (DuplicateKeyException e) {
            throw nameTaken(req.name());
        }
    }

    @Transactional
    public TemplateView update(UUID id, SaveTemplateRequest req) {
        long doctorId = doctors.currentDoctorId();
        try {
            int n = jdbc.sql("""
                    UPDATE doctor_template SET name = :name, content = CAST(:content AS jsonb), updated_at = now()
                     WHERE id = :id AND doctor_id = :doctorId
                    """)
                    .param("name", req.name().trim())
                    .param("content", toJson(req.content()))
                    .param("id", id)
                    .param("doctorId", doctorId)
                    .update();
            if (n == 0) {
                throw ApiException.notFound("Template");
            }
            return get(doctorId, id);
        } catch (DuplicateKeyException e) {
            throw nameTaken(req.name());
        }
    }

    @Transactional
    public void delete(UUID id) {
        long doctorId = doctors.currentDoctorId();
        int n = jdbc.sql("DELETE FROM doctor_template WHERE id = :id AND doctor_id = :doctorId")
                .param("id", id).param("doctorId", doctorId).update();
        if (n == 0) {
            throw ApiException.notFound("Template");
        }
    }

    /** Loads one of the doctor's own templates for applying to a visit, and counts the use. */
    @Transactional
    public TemplateContent use(long doctorId, UUID id) {
        TemplateView t = get(doctorId, id);
        jdbc.sql("UPDATE doctor_template SET use_count = use_count + 1 WHERE id = :id").param("id", id).update();
        return t.content();
    }

    /**
     * Fills a draft from a template without overwriting what the doctor already entered: empty text sections are
     * filled, and diagnoses, medicines and tests that aren't already there are added.
     */
    public static EncounterContent merge(EncounterContent draft, TemplateContent t, LocalDate visitDate) {
        EncounterContent.FollowUpEntry followUp = draft.followUp();
        if (followUp == null && t.followUpAfterDays() != null) {
            followUp = new EncounterContent.FollowUpEntry(visitDate.plusDays(t.followUpAfterDays()), null);
        }
        return new EncounterContent(
                firstNonBlank(draft.chiefComplaints(), t.chiefComplaints()),
                draft.historyOfPresentIllness(),
                draft.pastHistory(),
                firstNonBlank(draft.examination(), t.examination()),
                firstNonBlank(draft.treatmentGiven(), t.treatmentGiven()),
                firstNonBlank(draft.advice(), t.advice()),
                draft.privateNote(),
                draft.vitals(),
                union(draft.diagnoses(), t.diagnoses(), d -> key(d.code(), d.display())),
                union(draft.prescription(), t.prescription(),
                        r -> key(null, r.genericName() != null && !r.genericName().isBlank() ? r.genericName() : r.drugDisplay())),
                union(draft.investigations(), t.investigations(), i -> key(i.code(), i.display())),
                followUp);
    }

    private static <T> List<T> union(List<T> existing, List<T> added, Function<T, String> keyOf) {
        List<T> out = new ArrayList<>(existing);
        Set<String> seen = new HashSet<>();
        existing.forEach(e -> seen.add(keyOf.apply(e)));
        for (T a : added) {
            if (seen.add(keyOf.apply(a))) {
                out.add(a);
            }
        }
        return out;
    }

    private static String key(String code, String display) {
        if (code != null && !code.isBlank()) {
            return "code:" + code.trim().toUpperCase(Locale.ROOT);
        }
        return "name:" + (display == null ? "" : display.trim().toLowerCase(Locale.ROOT));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private TemplateView get(long doctorId, UUID id) {
        return jdbc.sql("""
                SELECT id, name, content::text AS content, use_count, updated_at FROM doctor_template
                 WHERE id = :id AND doctor_id = :doctorId
                """)
                .param("id", id)
                .param("doctorId", doctorId)
                .query(Row.class)
                .optional()
                .map(this::toView)
                .orElseThrow(() -> ApiException.notFound("Template"));
    }

    private TemplateView toView(Row r) {
        try {
            return new TemplateView(r.id(), r.name(), json.readValue(r.content(), TemplateContent.class), r.useCount(), r.updatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored template can't be read", e);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException nameTaken(String name) {
        return new ApiException(HttpStatus.CONFLICT, "TEMPLATE_NAME_TAKEN", "You already have a template called " + name.trim() + ".");
    }
}
