package com.clinicemr.productivity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.clinicemr.clinical.EncounterContent.DiagnosisEntry;
import com.clinicemr.clinical.EncounterContent.InvestigationEntry;
import com.clinicemr.clinical.EncounterContent.RxItem;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ProductivityDtos {

    private ProductivityDtos() {
    }

    /** A reusable visit outline, e.g. "Viral fever" or "Diabetes follow-up". Never includes vitals or patient details. */
    public record TemplateContent(
            @Size(max = 4000) String chiefComplaints,
            @Size(max = 8000) String examination,
            @Size(max = 4000) String advice,
            @Size(max = 20) List<@Valid DiagnosisEntry> diagnoses,
            @Size(max = 40) List<@Valid RxItem> prescription,
            @Size(max = 40) List<@Valid InvestigationEntry> investigations,
            @Min(1) @Max(365) Integer followUpAfterDays) {

        public TemplateContent {
            diagnoses = diagnoses == null ? List.of() : List.copyOf(diagnoses);
            prescription = prescription == null ? List.of() : List.copyOf(prescription);
            investigations = investigations == null ? List.of() : List.copyOf(investigations);
        }
    }

    public record SaveTemplateRequest(@NotBlank @Size(max = 80) String name, @NotNull @Valid TemplateContent content) {
    }

    public record TemplateView(UUID id, String name, TemplateContent content, int useCount, Instant updatedAt) {
    }

    /** Pin or unpin a favourite; exactly one of the entries matching {@code kind} must be set. */
    public record FavouriteRequest(
            @NotBlank @Pattern(regexp = "DIAGNOSIS|DRUG|INVESTIGATION") String kind,
            @Valid DiagnosisEntry diagnosis,
            @Valid RxItem drug,
            @Valid InvestigationEntry investigation,
            boolean pinned) {
    }

    public record FavouriteView(String kind, String ref, JsonNode entry, int useCount, boolean pinned, Instant lastUsedAt) {
    }
}
