package com.clinicemr.access;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.clinicemr.access.AccessDtos.GrantView;
import com.clinicemr.access.AccessDtos.Scope;
import com.clinicemr.notification.Notifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Record access grants: a clinic (optionally one doctor) may read a patient's records from other clinics, within a
 * scope, until a time. Every grant carries the consent it came from.
 */
@Service
public class GrantService {

    public static final String NOTICE_VERSION = "2026-10-v1";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Notifier notifier;

    public GrantService(JdbcClient jdbc, ObjectMapper json, Notifier notifier) {
        this.jdbc = jdbc;
        this.json = json;
        this.notifier = notifier;
    }

    /** Creates consent + grant and texts the approving number a confirmation. Runs in the caller's transaction. */
    public UUID create(long profileId, long clinicId, Long doctorId, Scope scope, String phone, Long accountId,
                       String method, UUID accessRequestId) {
        Instant expiresAt = Instant.now().plus(scope.duration().length);
        long consentId = jdbc.sql("""
                INSERT INTO consent (phone_e164, account_id, profile_id, purpose, method, details, notice_version)
                VALUES (:phone, :accountId, :profileId, 'CLINIC_HISTORY_ACCESS', :method, CAST(:details AS jsonb), :notice)
                RETURNING id
                """)
                .param("phone", phone)
                .param("accountId", accountId)
                .param("profileId", profileId)
                .param("method", method)
                .param("details", toJson(Map.of(
                        "clinicId", clinicId,
                        "doctorId", doctorId == null ? "any" : doctorId,
                        "scope", scope.types(),
                        "fromDate", scope.fromDate() == null ? "all" : scope.fromDate().toString(),
                        "duration", scope.duration().name())))
                .param("notice", NOTICE_VERSION)
                .query(Long.class)
                .single();
        UUID grantId = jdbc.sql("""
                INSERT INTO record_access_grant (profile_id, clinic_id, doctor_id, scope_types, scope_from_date, consent_id,
                                                 access_request_id, expires_at)
                VALUES (:profileId, :clinicId, :doctorId, :types, :fromDate, :consentId, :requestId, :expiresAt)
                RETURNING id
                """)
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .param("doctorId", doctorId)
                .param("types", scope.types().stream().map(Enum::name).sorted().toArray(String[]::new))
                .param("fromDate", scope.fromDate())
                .param("consentId", consentId)
                .param("requestId", accessRequestId)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .query(UUID.class)
                .single();

        record Names(String patientName, String clinicName) {
        }
        Names names = jdbc.sql("""
                SELECT (SELECT name FROM patient_profile WHERE id = :profileId) AS patient_name,
                       (SELECT name FROM clinic WHERE id = :clinicId) AS clinic_name
                """)
                .param("profileId", profileId)
                .param("clinicId", clinicId)
                .query(Names.class)
                .single();
        notifier.smsAfterCommit(phone, names.clinicName() + " can now view " + names.patientName()
                + "'s medical records until " + DATE_TIME.format(expiresAt.atZone(ZoneId.of("Asia/Kolkata")))
                + ". You can stop this anytime in the ClinicEMR app.");
        return grantId;
    }

    public List<GrantView> activeForClinicAndProfile(long clinicId, long profileId) {
        return query("g.clinic_id = :clinicId AND g.profile_id = :profileId", Map.of("clinicId", clinicId, "profileId", profileId));
    }

    public List<GrantView> activeForRequest(UUID requestId) {
        return query("g.access_request_id = :requestId", Map.of("requestId", requestId));
    }

    public List<GrantView> activeForProfiles(List<Long> profileIds) {
        if (profileIds.isEmpty()) {
            return List.of();
        }
        return query("g.profile_id IN (:profileIds)", Map.of("profileIds", profileIds));
    }

    public Optional<GrantView> activeById(UUID grantId) {
        return query("g.id = :grantId", Map.of("grantId", grantId)).stream().findFirst();
    }

    public void revoke(UUID grantId, String revokedBy) {
        jdbc.sql("""
                UPDATE record_access_grant SET revoked_at = now(), revoked_by = :by WHERE id = :id AND revoked_at IS NULL
                """)
                .param("id", grantId)
                .param("by", revokedBy)
                .update();
        jdbc.sql("""
                UPDATE consent SET withdrawn_at = now()
                 WHERE id = (SELECT consent_id FROM record_access_grant WHERE id = :id) AND withdrawn_at IS NULL
                """)
                .param("id", grantId)
                .update();
    }

    private List<GrantView> query(String where, Map<String, ?> params) {
        record Row(UUID grantId, UUID profileId, String patientName, long clinicId, String clinicName, String doctorName,
                   String scopeTypes, java.time.LocalDate fromDate, Instant expiresAt, Instant createdAt, String method,
                   String mrn) {
        }
        var spec = jdbc.sql("""
                SELECT g.id AS grant_id, p.public_id AS profile_id, p.name AS patient_name, c.id AS clinic_id,
                       c.name AS clinic_name, s.name AS doctor_name, array_to_string(g.scope_types, ',') AS scope_types,
                       g.scope_from_date AS from_date, g.expires_at, g.created_at, cn.method,
                       (SELECT mrn FROM patient_identifier pi WHERE pi.profile_id = g.profile_id AND pi.clinic_id = g.clinic_id) AS mrn
                  FROM record_access_grant g
                  JOIN patient_profile p ON p.id = g.profile_id
                  JOIN clinic c ON c.id = g.clinic_id
                  JOIN consent cn ON cn.id = g.consent_id
                  LEFT JOIN staff_user s ON s.id = g.doctor_id
                 WHERE g.revoked_at IS NULL AND g.expires_at > now() AND %s
                 ORDER BY g.created_at DESC
                """.formatted(where));
        for (var e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(Row.class).list().stream()
                .map(r -> new GrantView(r.grantId(), r.profileId(), r.patientName(), r.clinicId(), r.clinicName(),
                        r.doctorName(), toSet(r.scopeTypes()), r.fromDate(), r.expiresAt(), r.createdAt(), r.method(), r.mrn()))
                .toList();
    }

    private static Set<String> toSet(String csv) {
        return Arrays.stream(csv.split(",")).filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
