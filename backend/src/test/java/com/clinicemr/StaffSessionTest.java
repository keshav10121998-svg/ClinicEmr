package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Staff stay signed in for a month, renewed each time the app opens; renewing needs a valid staff sign-in. */
class StaffSessionTest extends IntegrationTestBase {

    @Test
    void renewGivesAFreshMonthAndNeedsASignIn() throws Exception {
        String email = uniqueEmail("renew");
        long clinic = registerClinic("Renew Clinic", email);
        String token = login(email);

        JsonNode renewed = body(perform(post("/api/v1/auth/staff/renew"), token, clinic, null).andExpect(status().isOk()));
        assertThat(Instant.parse(renewed.get("expiresAt").asText()))
                .isAfter(Instant.now().plus(Duration.ofDays(29)));
        assertThat(renewed.get("clinics").get(0).get("clinicId").asLong()).isEqualTo(clinic);
        // The new sign-in works
        perform(post("/api/v1/auth/staff/renew"), renewed.get("accessToken").asText(), clinic, null).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/staff/renew")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/staff/renew").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }
}
