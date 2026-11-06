package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.clinicemr.platform.SuperAdminSetup;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The super admin switches each clinic on for some months (free or at an agreed price). A clinic whose access has ended,
 * or that was never switched on, can still read its records but every change is refused with 402.
 */
class SubscriptionTest extends IntegrationTestBase {

    private static final String ADMIN_PASSWORD = "a-long-admin-password";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Test
    void superAdminSwitchesClinicsOnAndOffAndRecordsPayments() throws Exception {
        String boss = adminToken();
        String email = uniqueEmail("planowner");
        long clinic = registerClinic("Plan Clinic", email);
        String owner = login(email);
        String deskEmail = uniqueEmail("plandesk");
        perform(post("/api/v1/clinic/staff"), owner, clinic, """
                {"name": "Desk", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String desk = login(deskEmail);

        // Tests start clinics switched on for years; make this one "never switched on" like a real new sign-up
        setAccess(clinic, null);
        JsonNode plan = body(perform(get("/api/v1/clinic/plan"), owner, clinic, null).andExpect(status().isOk()));
        assertThat(plan.get("status").asText()).isEqualTo("NOT_ENABLED");
        assertThat(plan.get("blocked").asBoolean()).isTrue();
        assertThat(plan.has("accessUntil")).isFalse();

        JsonNode overview = body(asAdmin(get("/api/v1/admin/billing"), boss));
        JsonNode mine = find(overview.get("items"), clinic);
        assertThat(mine.get("plan").get("status").asText()).isEqualTo("NOT_ENABLED");
        assertThat(mine.get("ownerEmail").asText()).isEqualTo(email);
        assertThat(overview.get("notEnabled").asInt()).isGreaterThanOrEqualTo(1);
        // Clinics that need attention come first in the list
        assertThat(overview.get("items").get(0).get("plan").get("blocked").asBoolean()).isTrue();

        // Only the super admin can switch a clinic on, with sensible months and a price
        perform(post("/api/v1/admin/billing/clinics/" + clinic + "/grant"), owner, clinic, "{\"months\": 1, \"free\": true}")
                .andExpect(status().isUnauthorized());
        grant(boss, clinic, "{\"months\": 0, \"free\": true}").andExpect(status().isBadRequest());
        grant(boss, clinic, "{\"months\": 37, \"free\": true}").andExpect(status().isBadRequest());
        grant(boss, clinic, "{\"months\": 3, \"free\": false}").andExpect(status().isBadRequest());       // paid needs a price
        grant(boss, clinic, "{\"months\": 3, \"free\": false, \"monthlyPrice\": 0}").andExpect(status().isBadRequest());
        grant(boss, clinic, "{\"months\": 3, \"free\": false, \"monthlyPrice\": 50, \"amount\": -1}").andExpect(status().isBadRequest());

        // Three paid months at the agreed 50 a month: switched on from today
        JsonNode on = body(grant(boss, clinic, "{\"months\": 3, \"free\": false, \"monthlyPrice\": 50, \"note\": \"Agreed in person, UPI 1234\"}")
                .andExpect(status().isOk()));
        assertThat(on.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(on.get("blocked").asBoolean()).isFalse();
        assertThat(on.get("accessUntil").asText()).isEqualTo(TODAY.plusMonths(3).toString());
        assertThat(on.get("monthlyPrice").asInt()).isEqualTo(50);
        // The amount defaults to the price for the months chosen
        JsonNode history = body(asAdmin(get("/api/v1/admin/billing/clinics/" + clinic + "/payments"), boss));
        assertThat(history.get(0).get("amount").asInt()).isEqualTo(150);
        assertThat(history.get(0).get("note").asText()).isEqualTo("Agreed in person, UPI 1234");

        // More months while still on are added after the current end date; free months cost nothing
        on = body(grant(boss, clinic, "{\"months\": 2, \"free\": true}").andExpect(status().isOk()));
        assertThat(on.get("accessUntil").asText()).isEqualTo(TODAY.plusMonths(3).plusMonths(2).toString());
        assertThat(on.get("monthlyPrice").asInt()).isZero();

        // Admins see the price; other staff only see the dates
        assertThat(body(perform(get("/api/v1/clinic/plan"), owner, clinic, null)).has("monthlyPrice")).isTrue();
        assertThat(body(perform(get("/api/v1/clinic/plan"), desk, clinic, null)).has("monthlyPrice")).isFalse();

        // Close to the end it says so; then it ends and the clinic is behind
        setAccess(clinic, TODAY.plusDays(3));
        assertThat(body(perform(get("/api/v1/clinic/plan"), owner, clinic, null)).get("status").asText()).isEqualTo("EXPIRING");
        setAccess(clinic, TODAY);                                               // the last day still works
        assertThat(body(perform(get("/api/v1/clinic/plan"), owner, clinic, null)).get("blocked").asBoolean()).isFalse();
        setAccess(clinic, TODAY.minusDays(10));
        on = body(perform(get("/api/v1/clinic/plan"), owner, clinic, null));
        assertThat(on.get("status").asText()).isEqualTo("EXPIRED");

        // Switched on again after it ended: months count from today, not from the old date
        on = body(grant(boss, clinic, "{\"months\": 1, \"free\": false, \"monthlyPrice\": 70}").andExpect(status().isOk()));
        assertThat(on.get("accessUntil").asText()).isEqualTo(TODAY.plusMonths(1).toString());

        // Switching off ends access at once
        on = body(asAdmin(post("/api/v1/admin/billing/clinics/" + clinic + "/switch-off").contentType(MediaType.APPLICATION_JSON).content("{}"), boss));
        assertThat(on.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(on.get("blocked").asBoolean()).isTrue();

        mvc.perform(get("/api/v1/admin/billing")).andExpect(status().isUnauthorized());
    }

    @Test
    void aClinicWhoseAccessEndedCanReadButNotChange() throws Exception {
        String email = uniqueEmail("readonly");
        long clinic = registerClinic("Read Only Clinic", email);
        String doctor = login(email);
        String patientId = body(perform(post("/api/v1/clinic/patients"), doctor, clinic, """
                {"name": "Old Patient", "gender": "M", "ageYears": 50, "phone": "%s"}
                """.formatted(uniquePhone())).andExpect(status().isCreated())).get("publicId").asText();
        UUID visit = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctor, clinic, "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(visit, patientId))
                .andExpect(status().isCreated());

        for (LocalDate until : new LocalDate[] { TODAY.minusDays(1), null }) {
            setAccess(clinic, until);
            // Reading works: the patient list, the patient, the visit and the plan itself
            perform(get("/api/v1/clinic/patients/recent"), doctor, clinic, null).andExpect(status().isOk());
            perform(get("/api/v1/clinic/patients/" + patientId + "/summary"), doctor, clinic, null).andExpect(status().isOk());
            perform(get("/api/v1/clinic/encounters/" + visit), doctor, clinic, null).andExpect(status().isOk());
            perform(get("/api/v1/clinic/plan"), doctor, clinic, null).andExpect(status().isOk());

            // New visits, new patients and changes are refused with 402 and a clear message
            perform(post("/api/v1/clinic/encounters"), doctor, clinic, "{\"id\": \"%s\", \"patientId\": \"%s\"}"
                    .formatted(UUID.randomUUID(), patientId))
                    .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_ENDED"));
            perform(post("/api/v1/clinic/patients"), doctor, clinic, """
                    {"name": "New Patient", "gender": "F", "ageYears": 20, "phone": "%s"}
                    """.formatted(uniquePhone())).andExpect(status().isPaymentRequired());
            perform(put("/api/v1/clinic/encounters/" + visit), doctor, clinic, """
                    {"baseVersion": 0, "content": {"chiefComplaints": "Fever", "prescription": [], "investigations": [], "diagnoses": []}}
                    """).andExpect(status().isPaymentRequired());
            perform(post("/api/v1/clinic/encounters/" + visit + "/sign"), doctor, clinic, null).andExpect(status().isPaymentRequired());

            // Making a link to view a prescription only reads, so it is not blocked by the plan
            int linkStatus = perform(post("/api/v1/clinic/encounters/" + visit + "/prescription-link"), doctor, clinic, null)
                    .andReturn().getResponse().getStatus();
            assertThat(linkStatus).isNotEqualTo(402);
        }

        // Switched on again: changes work
        setAccess(clinic, TODAY.plusDays(30));
        perform(post("/api/v1/clinic/encounters"), doctor, clinic, "{\"id\": \"%s\", \"patientId\": \"%s\"}"
                .formatted(UUID.randomUUID(), patientId)).andExpect(status().isCreated());
    }

    private String adminToken() throws Exception {
        jdbc.sql("DELETE FROM platform_admin").update();
        new SuperAdminSetup(jdbc, passwords, "billing@example.com", ADMIN_PASSWORD, "Owner").run(null);
        return body(mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"billing@example.com\", \"password\": \"%s\"}".formatted(ADMIN_PASSWORD)))
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private void setAccess(long clinic, LocalDate until) {
        jdbc.sql("UPDATE clinic SET paid_until = :d WHERE id = :id").param("d", until).param("id", clinic).update();
    }

    private ResultActions grant(String admin, long clinic, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/billing/clinics/" + clinic + "/grant")
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
