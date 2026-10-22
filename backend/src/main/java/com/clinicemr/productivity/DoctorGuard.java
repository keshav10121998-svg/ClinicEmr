package com.clinicemr.productivity;

import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContextResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Templates and favourites belong to the doctor personally and follow them across clinics. */
@Component
class DoctorGuard {

    private final JdbcClient jdbc;

    DoctorGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long currentDoctorId() {
        long staffId = ClinicContextResolver.currentStaffId();
        boolean isDoctor = jdbc.sql("SELECT EXISTS (SELECT 1 FROM doctor WHERE staff_id = :id)")
                .param("id", staffId)
                .query(Boolean.class)
                .single();
        if (!isDoctor) {
            throw ApiException.forbidden("NOT_A_DOCTOR", "Templates and favourites are for doctors.");
        }
        return staffId;
    }
}
