package com.clinicemr.shared.audit;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Append-only audit trail for sensitive actions. Runs inside the caller's transaction,
 * so an audited change and its audit row commit or roll back together.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void staff(long staffId, Long clinicId, String action, String entityType, String entityId,
                      Map<String, ?> details) {
        record("STAFF", String.valueOf(staffId), clinicId, action, entityType, entityId, details);
    }

    public void record(String actorType, String actorId, Long clinicId, String action,
                       String entityType, String entityId, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_type, actor_id, clinic_id, action, entity_type, entity_id, details)
                VALUES (:actorType, :actorId, :clinicId, :action, :entityType, :entityId, CAST(:details AS jsonb))
                """)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("clinicId", clinicId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("details", toJson(details))
                .update();
    }

    private String toJson(Map<String, ?> details) {
        try {
            return json.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit details are not serialisable", e);
        }
    }
}
