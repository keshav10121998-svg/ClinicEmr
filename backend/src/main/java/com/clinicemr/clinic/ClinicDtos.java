package com.clinicemr.clinic;

import java.util.Set;

import com.clinicemr.shared.security.ClinicRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ClinicDtos {

    private ClinicDtos() {
    }

    public record ClinicInfo(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Pattern(regexp = "\\d{6}", message = "must be a 6-digit PIN code") String pincode,
            String phone) {
    }

    public record AdminInfo(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email String email,
            String phone,
            @NotBlank @Size(min = 10, max = 200, message = "must be at least 10 characters") String password,
            @Valid DoctorDetails doctor) {
    }

    public record RegisterClinicRequest(@NotNull @Valid ClinicInfo clinic, @NotNull @Valid AdminInfo admin) {
    }

    public record RegisterClinicResponse(long clinicId, String status, String next) {
    }

    public record AddStaffRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email String email,
            String phone,
            @Size(max = 200) String password,
            @NotEmpty Set<ClinicRole> roles,
            @Valid DoctorDetails doctor) {
    }
}
