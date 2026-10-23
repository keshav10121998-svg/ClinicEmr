package com.clinicemr.clinical;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class EncounterDtos {

    private EncounterDtos() {
    }

    public record StartEncounterRequest(
            @NotNull UUID id,
            @NotNull UUID patientId,
            @Pattern(regexp = "OPD|FOLLOW_UP|EMERGENCY|TELE") String visitType) {
    }

    /** Autosave. {@code baseVersion} is the draftVersion the client last saw; a mismatch returns 409. */
    public record SaveDraftRequest(int baseVersion, @NotNull EncounterContent content) {
    }

    public record AmendRequest(@NotNull EncounterContent content,
                               @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record DoctorRef(long staffId, String name) {
    }

    public record VersionInfo(int version, Instant signedAt, String amendmentReason, List<String> changedFields,
                              String contentHash) {
    }

    public record EncounterView(
            UUID id,
            UUID patientId,
            String status,
            String visitType,
            DoctorRef doctor,
            Instant startedAt,
            Instant signedAt,
            int draftVersion,
            int currentVersion,
            EncounterContent content,
            List<VersionInfo> versions,
            List<PrescriptionSafety.Warning> warnings) {
    }

    public record ApplyTemplateRequest(int baseVersion, @NotNull UUID templateId) {
    }

    /** One row of a patient's visit history: when, who, what was found, what treatment and which medicines. */
    public record EncounterListItem(UUID id, String visitType, Instant startedAt, Instant signedAt, String doctorName,
                                    int currentVersion, String diagnoses, String treatmentGiven, String medicines,
                                    int prescriptionLines) {
    }
}
