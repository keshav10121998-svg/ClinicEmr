package com.clinicemr.history;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.clinicemr.clinical.EncounterContent.RxItem;
import com.clinicemr.patient.PatientDtos.PatientView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class HistoryDtos {

    private HistoryDtos() {
    }

    // ----- allergies

    public record AddAllergyRequest(
            @Pattern(regexp = "DRUG|FOOD|ENVIRONMENT|OTHER") String category,
            @NotBlank @Size(max = 120) String substance,
            @Size(max = 300) String reaction,
            @Pattern(regexp = "MILD|MODERATE|SEVERE") String severity) {
    }

    public record ChangeAllergyStatusRequest(
            @NotBlank @Pattern(regexp = "INACTIVE|ENTERED_IN_ERROR") String status,
            @NotBlank @Size(max = 300) String reason) {
    }

    public record AllergyItem(UUID id, String category, String substance, String reaction, String severity,
                              String status, String recordedBy, Instant recordedAt) {
    }

    /**
     * {@code reviewed = false} means nobody has asked about allergies yet, which is different from
     * "no known allergies".
     */
    public record AllergyList(boolean reviewed, boolean noKnownAllergies, Instant reviewedAt, List<AllergyItem> allergies) {
    }

    // ----- summary

    public record ActiveProblem(String problemKey, String code, String display, Instant since, Instant lastSeen) {
    }

    public record CurrentMedicine(String medicationKey, String drugDisplay, String genericName, String dose,
                                  String frequency, String timing, boolean longTerm, LocalDate startedOn,
                                  LocalDate endsOn, String prescribedBy) {
    }

    public record VitalValue(String code, String display, BigDecimal value, String unit, Instant observedAt) {
    }

    public record LastVisit(UUID encounterId, Instant date, String doctorName, String diagnoses) {
    }

    public record PatientSummary(PatientView patient, AllergyList allergies, List<ActiveProblem> activeProblems,
                                 List<CurrentMedicine> currentMedicines, List<VitalValue> latestVitals,
                                 LastVisit lastVisit) {
    }

    public record VitalPoint(Instant observedAt, BigDecimal value, UUID encounterId) {
    }

    public record VitalSeries(String code, String display, String unit, List<VitalPoint> points) {
    }

    public record ResolveProblemRequest(@NotBlank String problemKey) {
    }

    public record StopMedicineRequest(@NotBlank String medicationKey, @Size(max = 300) String reason) {
    }

    public record LastPrescription(UUID encounterId, Instant date, String doctorName, List<RxItem> items) {
    }
}
