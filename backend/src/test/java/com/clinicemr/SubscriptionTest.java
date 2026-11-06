package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import com.clinicemr.platform.SuperAdminSetup;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A clinic's plan (free month, launch price, standard price, per-staff charge) and the super admin recording payments. */
class SubscriptionTest extends IntegrationTestBase {

    private static final String ADMIN_PASSWORD = "a-long-admin-password";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Test
    void planFollowsTheClinicAgeAndStaffAndPaymentsAreRecorded() throws Exception {
        jdbc.sql("DELETE FROM platform_admin").update();
        new SuperAdminSetup(jdbc, passwords, "billing@example.com", ADMIN_PASSWORD, "Owner").run(null);
        String boss = body(mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"billing@example.com\", \"password\": \"%s\"}".formatted(ADMIN_PASSWORD)))
                .andExpect(status().isOk())).get("accessToken").asText();

        String email = uniqueEmail("planowner");
        long clinic = registerClinic("Plan Clinic", email);
        String owner = login(email);
        String deskEmail = uniqueEmail("plandesk");
        perform(post("/api/v1/clinic/staff"), owner, clinic, """
                {"name": "Desk", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String nurseEmail = uniqueEmail("plannurse");
        perform(post("/api/v1/clinic/staff"), owner, clinic, """
                {"name": "Nurse", "email": "%s", "password": "%s", "roles": ["NURSE"]}
                """.formatted(nurseEmail, PASSWORD)).andExpect(status().isCreated());

        // A new clinic is in its free month: nothing to pay, and the price change is shown ahead
        JsonNode plan = body(perform(get("/api/v1/clinic/plan"), owner, clinic, null).andExpect(status().isOk()));
        assertThat(plan.get("stage").asText()).isEqualTo("FREE_MONTH");
        assertThat(plan.get("status").asText()).isEqualTo("FREE_MONTH");
        assertThat(plan.get("total").asInt()).isZero();
        assertThat(plan.get("billableStaff").asInt()).isEqualTo(2);
        assertThat(plan.get("nextChange").get("basePrice").asInt()).isEqualTo(50);
        assertThat(plan.get("nextAmount").asInt()).isEqualTo(50 + 2 * 20);

        // Only the admin of the clinic sees the plan
        perform(get("/api/v1/clinic/plan"), login(deskEmail), clinic, null).andExpect(status().isForbidden());
        perform(get("/api/v1/clinic/plan"), login(nurseEmail), clinic, null).andExpect(status().isForbidden());

        // Six weeks in: the free month is over and nothing is paid, so it is due, at the launch price
        setStart(clinic, TODAY.minusDays(45));
        plan = body(perform(get("/api/v1/clinic/plan"), owner, clinic, null));
        assertThat(plan.get("stage").asText()).isEqualTo("LAUNCH");
        assertThat(plan.get("status").asText()).isEqualTo("DUE");
        assertThat(plan.get("total").asInt()).isEqualTo(90);
        assertThat(plan.get("nextChange").get("basePrice").asInt()).isEqualTo(100);

        // The super admin sees it, with who to contact
        JsonNode overview = body(asAdmin(get("/api/v1/admin/billing"), boss));
        JsonNode mine = find(overview.get("items"), clinic);
        assertThat(mine.get("plan").get("status").asText()).isEqualTo("DUE");
        assertThat(mine.get("ownerEmail").asText()).isEqualTo(email);
        assertThat(overview.get("due").asInt()).isGreaterThanOrEqualTo(1);

        // Only the super admin can record a payment, for 1 to 12 months, with an amount
        perform(post("/api/v1/admin/billing/clinics/" + clinic + "/payments"), owner, clinic, "{\"amount\": 90, \"months\": 1}")
                .andExpect(status().isUnauthorized());
        record(boss, clinic, "{\"amount\": -1, \"months\": 1}").andExpect(status().isBadRequest());
        record(boss, clinic, "{\"amount\": 90, \"months\": 0}").andExpect(status().isBadRequest());
        record(boss, clinic, "{\"amount\": 90, \"months\": 13}").andExpect(status().isBadRequest());

        // A late clinic is covered from today: 3 months paid
        JsonNode paid = body(record(boss, clinic, "{\"amount\": 270, \"months\": 3, \"note\": \"UPI 1234\"}").andExpect(status().isOk()));
        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("paidUntil").asText()).isEqualTo(TODAY.plusMonths(3).toString());
        // More time paid for is added to the end of what is already covered
        paid = body(record(boss, clinic, "{\"amount\": 90, \"months\": 1}").andExpect(status().isOk()));
        assertThat(paid.get("paidUntil").asText()).isEqualTo(TODAY.plusMonths(4).toString());
        JsonNode history = body(asAdmin(get("/api/v1/admin/billing/clinics/" + clinic + "/payments"), boss));
        assertThat(history).hasSize(2);
        assertThat(history.get(1).get("note").asText()).isEqualTo("UPI 1234");
        assertThat(history.get(1).get("coversUntil").asText()).isEqualTo(TODAY.plusMonths(3).toString());

        // A staff member who is switched off stops being charged
        long nurse = jdbc.sql("SELECT id FROM staff_user WHERE email = :e").param("e", nurseEmail).query(Long.class).single();
        jdbc.sql("UPDATE staff_user SET status = 'DISABLED' WHERE id = :id").param("id", nurse).update();
        assertThat(body(perform(get("/api/v1/clinic/plan"), owner, clinic, null)).get("total").asInt()).isEqualTo(70);

        // After seven months the standard price applies, with no further change ahead
        setStart(clinic, TODAY.minusMonths(9));
        plan = body(perform(get("/api/v1/clinic/plan"), owner, clinic, null));
        assertThat(plan.get("stage").asText()).isEqualTo("STANDARD");
        assertThat(plan.get("total").asInt()).isEqualTo(100 + 20);
        assertThat(plan.has("nextChange")).isFalse();

        // Without a sign-in the billing screens are closed
        mvc.perform(get("/api/v1/admin/billing")).andExpect(status().isUnauthorized());
    }

    private void setStart(long clinic, LocalDate start) {
        jdbc.sql("UPDATE clinic SET billing_started_on = :d WHERE id = :id").param("d", start).param("id", clinic).update();
    }

    private ResultActions record(String admin, long clinic, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/billing/clinics/" + clinic + "/payments")
                .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder req, String token) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private static JsonNode find(JsonNode items, long clinicId) {
        for (JsonNode n : items) {
            if (n.get("plan").get("clinicId").asLong() == clinicId) return n;
        }
        return null;
    }
}
