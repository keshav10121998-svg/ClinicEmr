package com.clinicemr.shared.security;

import java.util.Arrays;
import java.util.Set;

import com.clinicemr.shared.error.ApiException;

/**
 * The signed-in staff member acting for one clinic (chosen with the {@code X-Clinic-Id} header).
 * Resolved per request from the JWT and the clinic_member table, so role changes apply immediately.
 */
public record ClinicContext(long staffId, long clinicId, Set<ClinicRole> roles) {

    public boolean has(ClinicRole role) {
        return roles.contains(role);
    }

    /** Throws 403 unless the staff member holds at least one of the given roles in this clinic. */
    public void require(ClinicRole... anyOf) {
        if (Arrays.stream(anyOf).noneMatch(roles::contains)) {
            throw ApiException.forbidden("ROLE_REQUIRED",
                    "Your role in this clinic doesn't allow this. Needed: " + Arrays.toString(anyOf));
        }
    }
}
