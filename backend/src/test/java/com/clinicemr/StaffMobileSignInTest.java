package com.clinicemr;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Staff sign in with email or their 10-digit mobile number; every mobile number has exactly 10 digits. */
class StaffMobileSignInTest extends IntegrationTestBase {

    @Test
    void signInWithMobileNumber() throws Exception {
        String adminEmail = uniqueEmail("mobileadmin");
        long clinic = registerClinic("Mobile Clinic", adminEmail);
        String admin = login(adminEmail);

        // A doctor added with a mobile number signs in with it, typed any common way
        String phone = uniquePhone();
        long doctorId = body(perform(post("/api/v1/clinic/staff"), admin, clinic, """
                {"name": "Dr. Rao", "email": "%s", "phone": "%s", "password": "%s", "roles": ["DOCTOR"],
                 "doctor": {"registrationNo": "UPMC-1", "registrationCouncil": "UP Medical Council", "qualifications": "MBBS"}}
                """.formatted(uniqueEmail("rao"), phone, PASSWORD))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.phone").value(phone))).get("staffId").asLong();
        signIn(phone, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Dr. Rao"));
        signIn("+91 " + phone.substring(0, 5) + " " + phone.substring(5), PASSWORD).andExpect(status().isOk());
        signIn(phone, "wrong-password-123").andExpect(status().isUnauthorized());
        // Older clients still send "email"
        mvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(adminEmail, PASSWORD))).andExpect(status().isOk());

        // Not 10 digits: refused as a sign-in name, and as a mobile number anywhere
        signIn(phone.substring(1), PASSWORD).andExpect(status().isBadRequest());
        signIn(phone + "1", PASSWORD).andExpect(status().isBadRequest());
        perform(post("/api/v1/clinic/patients"), admin, clinic, """
                {"name": "Short Number", "gender": "M", "ageYears": 30, "phone": "98765432"}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        perform(post("/api/v1/clinic/patients"), admin, clinic, """
                {"name": "Long Number", "gender": "M", "ageYears": 30, "phone": "987654321012"}
                """).andExpect(status().isBadRequest());

        // One account per mobile number
        perform(post("/api/v1/clinic/staff"), admin, clinic, """
                {"name": "Copy", "email": "%s", "phone": "%s", "password": "%s", "roles": ["NURSE"]}
                """.formatted(uniqueEmail("copy"), phone, PASSWORD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHONE_IN_USE"));

        // Admins add a mobile to an existing account (here their own), then it works for sign-in
        long adminId = body(signIn(adminEmail, PASSWORD)).get("staffId").asLong();
        String adminPhone = uniquePhone();
        perform(put("/api/v1/clinic/staff/" + adminId + "/phone"), admin, clinic, "{\"phone\": \"%s\"}".formatted(adminPhone))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value(adminPhone));
        signIn(adminPhone, PASSWORD).andExpect(status().isOk());
        perform(put("/api/v1/clinic/staff/" + adminId + "/phone"), admin, clinic, "{\"phone\": \"%s\"}".formatted(phone))
                .andExpect(status().isConflict());
        perform(put("/api/v1/clinic/staff/" + adminId + "/phone"), admin, clinic, "{\"phone\": \"12345\"}")
                .andExpect(status().isBadRequest());
        // Removing it: back to email only
        perform(put("/api/v1/clinic/staff/" + doctorId + "/phone"), admin, clinic, "{\"phone\": \"\"}").andExpect(status().isOk());
        signIn(phone, PASSWORD).andExpect(status().isUnauthorized());
    }

    private ResultActions signIn(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password)));
    }
}
