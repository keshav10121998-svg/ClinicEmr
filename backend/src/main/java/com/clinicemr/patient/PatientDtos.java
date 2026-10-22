package com.clinicemr.patient;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PatientDtos {

    private PatientDtos() {
    }

    public record RegisterPatientRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "[MFOU]", message = "must be M, F, O or U") String gender,
            @Past LocalDate dob,
            @Min(0) @Max(130) Integer ageYears,
            @NotBlank String phone,
            @Pattern(regexp = "\\d{2}-?\\d{4}-?\\d{4}-?\\d{4}", message = "must be a 14-digit ABHA number") String abhaNumber,
            @Pattern(regexp = "(A|B|AB|O)[+-]", message = "must look like O+ or AB-") String bloodGroup,
            boolean confirmNew) {
    }

    public record PhoneSearchRequest(@NotBlank String phone) {
    }

    public record PatientView(UUID publicId, String mrn, String name, String gender, LocalDate dob,
                              boolean dobEstimated, Integer ageYears, String phone, String bloodGroup,
                              boolean hasAbha) {
    }

    public record DuplicateCandidate(UUID publicId, String mrn, String name, String gender, Integer ageYears,
                                     double nameSimilarity) {
    }

    static Integer age(LocalDate dob) {
        return dob == null ? null : Period.between(dob, LocalDate.now()).getYears();
    }
}
