package com.clinicemr.access;

import java.util.UUID;

import com.clinicemr.shared.security.ClinicContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Records every look at a patient's shared records. Patients can read this log in the app. */
@Component
public class AccessLog {

    private final JdbcClient jdbc;

    public AccessLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Logs a read only the first time for this entity under this grant (for reads that repeat, like autosave). */
    public void staffReadOnce(ClinicContext ctx, long profileId, String action, String entityType, String entityId, UUID grantId) {
        boolean logged = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM access_log WHERE profile_id = :profileId AND clinic_id = :clinicId
                                AND action = :action AND entity_id = :entityId AND via_grant_id = :grantId)
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("action", action)
                .param("entityId", entityId)
                .param("grantId", grantId)
                .query(Boolean.class)
                .single();
        if (!logged) {
            staffRead(ctx, profileId, action, entityType, entityId, grantId);
        }
    }

    public void staffRead(ClinicContext ctx, long profileId, String action, String entityType, String entityId, UUID grantId) {
        jdbc.sql("""
                INSERT INTO access_log (profile_id, clinic_id, actor_type, actor_id, action, entity_type, entity_id, via_grant_id)
                VALUES (:profileId, :clinicId, 'STAFF', :actorId, :action, :entityType, :entityId, :grantId)
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("actorId", String.valueOf(ctx.staffId()))
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("grantId", grantId)
                .update();
    }
}
