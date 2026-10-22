package com.clinicemr.patientapp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.access.AccessDtos.AccessLogEntry;
import com.clinicemr.access.AccessDtos.ApproveRequest;
import com.clinicemr.access.AccessDtos.GrantView;
import com.clinicemr.access.AccessDtos.PendingRequest;
import com.clinicemr.access.GrantService;
import com.clinicemr.patient.PatientService;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.PatientContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What a patient controls in the app: their family's profiles, clinic requests, active access and who looked. */
@Service
public class PatientAppService {

    private final JdbcClient jdbc;
    private final GrantService grants;
    private final PatientService patients;
    private final AuditService audit;

    public PatientAppService(JdbcClient jdbc, GrantService grants, PatientService patients, AuditService audit) {
        this.jdbc = jdbc;
        this.grants = grants;
        this.patients = patients;
        this.audit = audit;
    }

    public record ProfileView(UUID profileId, String name, String gender, Integer ageYears, String role, String relation) {
    }

    private record ProfileRow(long id, UUID publicId, String name, String gender, LocalDate dob, String role, String relation) {
    }

    private List<ProfileRow> profileRows(PatientContext p) {
        return jdbc.sql("""
                SELECT pp.id, pp.public_id, pp.name, pp.gender, pp.dob, pa.role, pa.relation
                  FROM profile_access pa JOIN patient_profile pp ON pp.id = pa.profile_id
                 WHERE pa.account_id = :accountId AND pp.deleted_at IS NULL AND pp.merged_into_id IS NULL
                 ORDER BY pa.role DESC, pp.name
                """)
                .param("accountId", p.accountId())
                .query(ProfileRow.class)
                .list();
    }

    public List<ProfileView> profiles(PatientContext p) {
        return profileRows(p).stream()
                .map(r -> new ProfileView(r.publicId(), r.name(), r.gender(),
                        r.dob() == null ? null : Period.between(r.dob(), LocalDate.now()).getYears(), r.role(), r.relation()))
                .toList();
    }

    public List<PendingRequest> pendingRequests(PatientContext p) {
        return jdbc.sql("""
                SELECT r.id AS request_id, c.name AS clinic_name, c.city AS clinic_city, s.name AS requested_by,
                       r.created_at, r.expires_at
                  FROM access_request r JOIN clinic c ON c.id = r.clinic_id JOIN staff_user s ON s.id = r.staff_id
                 WHERE r.phone_e164 = :phone AND r.status = 'PENDING' AND r.expires_at > now()
                 ORDER BY r.created_at DESC
                """)
                .param("phone", p.phoneE164())
                .query(PendingRequest.class)
                .list();
    }

    @Transactional
    public List<GrantView> approve(PatientContext p, UUID requestId, ApproveRequest req) {
        record Req(long clinicId, String status, Instant expiresAt) {
        }
        Req r = lockPending(p, requestId, Req.class);
        if (!"PENDING".equals(r.status()) || Instant.now().isAfter(r.expiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CLOSED", "This request has already been answered or has expired.");
        }
        Map<UUID, Long> mine = ownProfileIds(p);
        for (UUID profileId : req.profileIds()) {
            Long internalId = mine.get(profileId);
            if (internalId == null) {
                throw ApiException.forbidden("NOT_YOUR_PROFILE", "You can only share profiles you manage.");
            }
            patients.linkToClinic(r.clinicId(), internalId, "PATIENT", "account:" + p.accountId());
            grants.create(internalId, r.clinicId(), null, req.scope(), p.phoneE164(), p.accountId(), "APP", requestId);
        }
        jdbc.sql("UPDATE access_request SET status = 'APPROVED', verified_at = now() WHERE id = :id")
                .param("id", requestId).update();
        audit.record("PATIENT", "account:" + p.accountId(), r.clinicId(), "ACCESS_APPROVED_IN_APP", "access_request",
                requestId.toString(), Map.of("profiles", req.profileIds().size(), "duration", req.scope().duration().name()));
        return grants.activeForRequest(requestId);
    }

    @Transactional
    public void deny(PatientContext p, UUID requestId) {
        record Req(long clinicId, String status, Instant expiresAt) {
        }
        Req r = lockPending(p, requestId, Req.class);
        if (!"PENDING".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CLOSED", "This request has already been answered.");
        }
        jdbc.sql("UPDATE access_request SET status = 'DENIED' WHERE id = :id").param("id", requestId).update();
        audit.record("PATIENT", "account:" + p.accountId(), r.clinicId(), "ACCESS_DENIED_IN_APP", "access_request",
                requestId.toString(), Map.of());
    }

    public List<GrantView> activeGrants(PatientContext p) {
        return grants.activeForProfiles(List.copyOf(ownProfileIds(p).values()));
    }

    @Transactional
    public void revoke(PatientContext p, UUID grantId) {
        GrantView g = grants.activeById(grantId)
                .filter(v -> ownProfileIds(p).containsKey(v.profileId()))
                .orElseThrow(() -> ApiException.notFound("Active access"));
        grants.revoke(grantId, "account:" + p.accountId());
        audit.record("PATIENT", "account:" + p.accountId(), g.clinicId(), "ACCESS_REVOKED", "record_access_grant",
                grantId.toString(), Map.of());
    }

    public List<AccessLogEntry> accessLog(PatientContext p, UUID profileId) {
        Map<UUID, Long> mine = ownProfileIds(p);
        List<Long> ids = profileId == null ? List.copyOf(mine.values())
                : List.of(java.util.Optional.ofNullable(mine.get(profileId))
                        .orElseThrow(() -> ApiException.notFound("Profile")));
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT l.at, pp.name AS patient_name, c.name AS clinic_name,
                       CASE WHEN l.actor_type = 'STAFF' THEN (SELECT name FROM staff_user WHERE id = l.actor_id::bigint)
                            ELSE l.actor_type END AS actor_name,
                       l.action
                  FROM access_log l
                  JOIN patient_profile pp ON pp.id = l.profile_id
                  LEFT JOIN clinic c ON c.id = l.clinic_id
                 WHERE l.profile_id IN (:ids)
                 ORDER BY l.at DESC
                 LIMIT 200
                """)
                .param("ids", ids)
                .query(AccessLogEntry.class)
                .list();
    }

    private Map<UUID, Long> ownProfileIds(PatientContext p) {
        Map<UUID, Long> out = new java.util.LinkedHashMap<>();
        profileRows(p).forEach(r -> out.put(r.publicId(), r.id()));
        return out;
    }

    private <T> T lockPending(PatientContext p, UUID requestId, Class<T> type) {
        return jdbc.sql("""
                SELECT clinic_id, status, expires_at FROM access_request WHERE id = :id AND phone_e164 = :phone FOR UPDATE
                """)
                .param("id", requestId)
                .param("phone", p.phoneE164())
                .query(type)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Request"));
    }
}
