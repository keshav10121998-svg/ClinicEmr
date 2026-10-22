package com.clinicemr.access;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.clinicemr.shared.security.ClinicContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single place that decides whether a clinic may read records created at other clinics: only while an active,
 * unrevoked grant exists for that patient and clinic (and, for doctor-only grants, that doctor).
 * <p>
 * A clinic's own records are always visible to its own staff; that check lives with each module.
 */
@Component
public class ClinicalAccessPolicy {

    private final JdbcClient jdbc;

    public ClinicalAccessPolicy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The combined access from all of the viewer's active grants for this patient. When several grants apply, their
     * scope types are joined and the earliest start date wins.
     */
    public record EffectiveAccess(List<UUID> grantIds, Set<String> types, LocalDate fromDate) {
        public boolean allows(String type) {
            return types.contains(type);
        }

        /** The grant to record in the access log. */
        public UUID primaryGrant() {
            return grantIds.get(0);
        }
    }

    public Optional<EffectiveAccess> effectiveAccess(ClinicContext ctx, long profileId) {
        record Row(UUID id, String scopeTypes, LocalDate fromDate) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT id, array_to_string(scope_types, ',') AS scope_types, scope_from_date AS from_date
                  FROM record_access_grant
                 WHERE profile_id = :profileId AND clinic_id = :clinicId
                   AND revoked_at IS NULL AND expires_at > now()
                   AND (doctor_id IS NULL OR doctor_id = :staffId)
                 ORDER BY expires_at DESC
                """)
                .param("profileId", profileId)
                .param("clinicId", ctx.clinicId())
                .param("staffId", ctx.staffId())
                .query(Row.class)
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Set<String> types = new java.util.TreeSet<>();
        LocalDate from = null;
        boolean fullHistory = false;
        for (Row r : rows) {
            types.addAll(List.of(r.scopeTypes().split(",")));
            if (r.fromDate() == null) {
                fullHistory = true;
            } else if (from == null || r.fromDate().isBefore(from)) {
                from = r.fromDate();
            }
        }
        return Optional.of(new EffectiveAccess(rows.stream().map(Row::id).toList(), types, fullHistory ? null : from));
    }
}
