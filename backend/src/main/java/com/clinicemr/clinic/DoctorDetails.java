package com.clinicemr.clinic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Details printed on prescriptions; required for anyone with the DOCTOR role. */
public record DoctorDetails(
        @NotBlank @Size(max = 50) String registrationNo,
        @NotBlank @Size(max = 120) String registrationCouncil,
        @NotBlank @Size(max = 200) String qualifications,
        @Size(max = 120) String specialty) {
}
