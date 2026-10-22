package com.clinicemr.clinical;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Everything a doctor records in one visit. Drafts may be incomplete; the constraints are enforced when the
 * visit is signed or amended.
 */
public record EncounterContent(
        @Size(max = 4000) String chiefComplaints,
        @Size(max = 8000) String historyOfPresentIllness,
        @Size(max = 8000) String pastHistory,
        @Size(max = 8000) String examination,
        @Size(max = 4000) String advice,
        @Size(max = 8000) String privateNote,
        @Valid Vitals vitals,
        @Size(max = 20) List<@Valid DiagnosisEntry> diagnoses,
        @Size(max = 40) List<@Valid RxItem> prescription,
        @Size(max = 40) List<@Valid InvestigationEntry> investigations,
        @Valid FollowUpEntry followUp) {

    public EncounterContent {
        diagnoses = diagnoses == null ? List.of() : List.copyOf(diagnoses);
        prescription = prescription == null ? List.of() : List.copyOf(prescription);
        investigations = investigations == null ? List.of() : List.copyOf(investigations);
    }

    public static EncounterContent empty() {
        return new EncounterContent(null, null, null, null, null, null, null, null, null, null, null);
    }

    public record Vitals(
            @Min(50) @Max(300) Integer bpSystolic,
            @Min(20) @Max(200) Integer bpDiastolic,
            @Min(20) @Max(250) Integer pulse,
            @DecimalMin("90.0") @DecimalMax("110.0") BigDecimal temperatureF,
            @Min(50) @Max(100) Integer spo2,
            @DecimalMin("0.3") @DecimalMax("400.0") BigDecimal weightKg,
            @DecimalMin("30.0") @DecimalMax("250.0") BigDecimal heightCm,
            @Min(5) @Max(80) Integer respiratoryRate,
            @DecimalMin("10") @DecimalMax("1000") BigDecimal bloodGlucoseMgDl) {
    }

    public record DiagnosisEntry(
            @Pattern(regexp = "ICD10|SNOMED") String codeSystem,
            @Size(max = 20) String code,
            @NotBlank @Size(max = 300) String display,
            @Pattern(regexp = "PRIMARY|SECONDARY|PROVISIONAL") String kind,
            boolean chronic) {
    }

    public record RxItem(
            Long drugId,
            @NotBlank @Size(max = 200) String drugDisplay,
            @Size(max = 200) String genericName,
            @Size(max = 60) String dose,
            @NotBlank @Size(max = 60) String frequency,
            @Size(max = 40) String route,
            @Size(max = 60) String timing,
            @Min(1) @Max(365) Integer durationDays,
            boolean longTerm,
            @Size(max = 300) String instructions) {
    }

    public record InvestigationEntry(@Size(max = 20) String code, @NotBlank @Size(max = 200) String display) {
    }

    public record FollowUpEntry(@NotNull LocalDate dueDate, @Size(max = 300) String note) {
    }
}
