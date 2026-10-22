package com.clinicemr.access;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AccessDtos {

    private AccessDtos() {
    }

    public enum ScopeType { VISITS, DOCUMENTS }

    public enum Duration {
        VISIT(java.time.Duration.ofHours(24)),
        DAYS_30(java.time.Duration.ofDays(30)),
        YEAR_1(java.time.Duration.ofDays(365));

        public final java.time.Duration length;

        Duration(java.time.Duration length) {
            this.length = length;
        }
    }

    /** What the patient agrees to share. */
    public record Scope(@NotEmpty Set<ScopeType> types, @Past LocalDate fromDate, @NotNull Duration duration) {
    }

    /** Start a request either from a phone number or from a patient this clinic already has. */
    public record StartAccessRequest(String phone, UUID patientId) {
    }

    public record AccessRequestView(UUID requestId, String phone, String status, Instant expiresAt,
                                    List<SharedProfile> profiles, List<GrantView> grants, String next) {
    }

    public record VerifyRequest(@NotNull @Pattern(regexp = "\\d{6}", message = "must be the 6-digit code") String otp) {
    }

    /** A person on the approving phone number. Shown only after the patient approved. */
    public record SharedProfile(UUID profileId, String name, String gender, Integer ageYears, boolean linkedToThisClinic,
                                String mrn) {
    }

    public record ClinicGrantRequest(@NotNull UUID profileId, @NotNull @Valid Scope scope, boolean onlyMe) {
    }

    public record GrantView(UUID grantId, UUID profileId, String patientName, long clinicId, String clinicName,
                            String doctorName, Set<String> scopeTypes, LocalDate fromDate, Instant expiresAt,
                            Instant createdAt, String method, String mrn) {
    }

    // ----- patient app

    public record ApproveRequest(@NotEmpty @Size(max = 10) List<UUID> profileIds, @NotNull @Valid Scope scope) {
    }

    public record PendingRequest(UUID requestId, String clinicName, String clinicCity, String requestedBy,
                                 Instant createdAt, Instant expiresAt) {
    }

    public record AccessLogEntry(Instant at, String patientName, String clinicName, String actorName, String action) {
    }
}
