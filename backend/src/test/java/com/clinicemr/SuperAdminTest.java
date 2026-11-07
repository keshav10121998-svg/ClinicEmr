package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** The super admin: made once from settings, sees every clinic and doctor, and removes test doctors and visits. */
class SuperAdminTest extends IntegrationTestBase {

    static final String ADMIN_PASSWORD = "a-long-admin-password";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Test
    void setupRunsOnlyTheFirstTime() throws Exception {
        jdbc.sql("DELETE FROM platform_admin").update();
        new SuperAdminSetup(jdbc, passwords, "boss@example.com", "short", "").run(null);
        assertThat(admins()).isZero();                      // password too short: nothing made
        new SuperAdminSetup(jdbc, passwords, "Boss@Example.com", ADMIN_PASSWORD, "Owner").run(null);
        assertThat(admins()).isEqualTo(1);
        new SuperAdminSetup(jdbc, passwords, "other@example.com", ADMIN_PASSWORD, "").run(null);
        assertThat(admins()).isEqualTo(1);                  // already set up: settings ignored

        assertThat(adminLogin("boss@example.com", ADMIN_PASSWORD).andExpect(status().isOk()))
                .isNotNull();
        for (int i = 0; i < 5; i++) {
            adminLogin("boss@example.com", "wrong-password").andExpect(status().isUnauthorized());
        }
        adminLogin("boss@example.com", ADMIN_PASSWORD).andExpect(status().isTooManyRequests());
        jdbc.sql("UPDATE platform_admin SET locked_until = NULL").update();
    }

    @Test
    void seesEverythingAndRemovesTestDoctors() throws Exception {
        jdbc.sql("DELETE FROM platform_admin").update();
        new SuperAdminSetup(jdbc, passwords, "owner@example.com", ADMIN_PASSWORD, "Owner").run(null);
        String admin = body(adminLogin("owner@example.com", ADMIN_PASSWORD).andExpect(status().isOk())).get("accessToken").asText();

        String email = uniqueEmail("dummy");
        long clinic = registerClinic("Dummy Clinic", email);
        String doctor = login(email);
        String patient = body(perform(post("/api/v1/clinic/patients"), doctor, clinic, """
                {"name": "Dummy Patient", "gender": "M", "ageYears": 30, "phone": "%s"}
                """.formatted(uniquePhone())).andExpect(status().isCreated())).get("publicId").asText();
        UUID keep = visit(doctor, clinic, patient);
        UUID remove = visit(doctor, clinic, patient);

        // Clinic and patient tokens can't use the admin screens, and the admin token can't act as a clinic
        mvc.perform(get("/api/v1/admin/overview").header("Authorization", "Bearer " + doctor)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
        perform(get("/api/v1/clinic/patients/recent"), admin, clinic, null).andExpect(status().isUnauthorized());

        JsonNode before = body(asAdmin(get("/api/v1/admin/overview"), admin));
        long id = staffId(email);
        JsonNode list = body(asAdmin(get("/api/v1/admin/doctors"), admin));
        JsonNode row = find(list, id);
        assertThat(row.get("visits").asInt()).isEqualTo(2);
        assertThat(row.get("patients").asInt()).isEqualTo(1);
        assertThat(row.get("clinics").asText()).isEqualTo("Dummy Clinic");

        // One visit removed with a reason
        mvc.perform(post("/api/v1/admin/visits/" + remove + "/delete").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Test visit\"}")).andExpect(status().isNoContent());
        JsonNode detail = body(asAdmin(get("/api/v1/admin/doctors/" + id), admin));
        assertThat(detail.get("doctor").get("visits").asInt()).isEqualTo(1);
        assertThat(detail.get("visits")).hasSize(2);
        assertThat(detail.get("visits").findValuesAsText("deletedReason")).contains("Test visit");
        assertThat(body(perform(get("/api/v1/clinic/patients/" + patient + "/encounters"), doctor, clinic, null))).hasSize(1);

        // The doctor removed: can't sign in, the emptied clinic and its patients go too, and the email is free again
        JsonNode result = body(mvc.perform(post("/api/v1/admin/doctors/" + id + "/delete").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"alsoVisits\": true}")).andExpect(status().isOk()));
        assertThat(result.get("visitsDeleted").asInt()).isEqualTo(1);
        assertThat(result.get("clinicsDeleted").get(0).asText()).isEqualTo("Dummy Clinic");
        assertThat(result.get("patientsDeleted").asInt()).isEqualTo(1);
        mvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(email, PASSWORD))).andExpect(status().isUnauthorized());
        perform(get("/api/v1/clinic/patients/recent"), doctor, clinic, null).andExpect(status().isForbidden());

        JsonNode after = body(asAdmin(get("/api/v1/admin/overview"), admin));
        assertThat(after.get("doctors").asInt()).isEqualTo(before.get("doctors").asInt() - 1);
        assertThat(after.get("clinics").asInt()).isEqualTo(before.get("clinics").asInt() - 1);
        assertThat(after.get("patients").asInt()).isEqualTo(before.get("patients").asInt() - 1);
        assertThat(after.get("visits").asInt()).isEqualTo(before.get("visits").asInt() - 2);
        assertThat(find(body(asAdmin(get("/api/v1/admin/doctors"), admin)), id)).isNull();
        assertThat(find(body(asAdmin(get("/api/v1/admin/doctors?deleted=true"), admin)), id).get("email").asText()).isEqualTo(email);
        registerClinic("Real Clinic", email);
        assertThat(keep).isNotNull();
    }

    @Test
    void permanentDeleteOnlyAfterRemoveAndErasesEverything() throws Exception {
        jdbc.sql("DELETE FROM platform_admin").update();
        new SuperAdminSetup(jdbc, passwords, "purge@example.com", ADMIN_PASSWORD, "Owner").run(null);
        String admin = body(adminLogin("purge@example.com", ADMIN_PASSWORD).andExpect(status().isOk())).get("accessToken").asText();

        String email = uniqueEmail("purge");
        long clinic = registerClinic("Purge Clinic", email);
        String doctor = login(email);
        String patient = body(perform(post("/api/v1/clinic/patients"), doctor, clinic, """
                {"name": "Purge Patient", "gender": "F", "ageYears": 40, "phone": "%s"}
                """.formatted(uniquePhone())).andExpect(status().isCreated())).get("publicId").asText();
        UUID first = visit(doctor, clinic, patient);
        UUID second = visit(doctor, clinic, patient);
        perform(put("/api/v1/clinic/encounters/" + first + "/payment"), doctor, clinic, """
                {"paid": 300, "balance": 0}
                """).andExpect(status().isOk());
        long id = staffId(email);

        // Not removed yet: refused
        adminPost("/api/v1/admin/visits/" + first + "/purge", admin).andExpect(status().isConflict());
        adminPost("/api/v1/admin/doctors/" + id + "/purge", admin).andExpect(status().isConflict());

        // A removed visit is erased with its payment and medicines
        adminPost("/api/v1/admin/visits/" + first + "/delete", admin).andExpect(status().isNoContent());
        JsonNode one = body(adminPost("/api/v1/admin/visits/" + first + "/purge", admin).andExpect(status().isOk()));
        assertThat(one.get("visits").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM encounter WHERE id = '" + first + "'")).isZero();
        assertThat(count("SELECT count(*) FROM payment_entry WHERE encounter_id = '" + first + "'")).isZero();

        // A removed doctor is erased with the emptied clinic, its patients and remaining visits
        adminPost("/api/v1/admin/doctors/" + id + "/delete", admin).andExpect(status().isOk());
        JsonNode all = body(adminPost("/api/v1/admin/doctors/" + id + "/purge", admin).andExpect(status().isOk()));
        assertThat(all.get("clinics").asInt()).isEqualTo(1);
        assertThat(all.get("patients").asInt()).isEqualTo(1);
        assertThat(all.get("visits").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM staff_user WHERE id = " + id)).isZero();
        assertThat(count("SELECT count(*) FROM clinic WHERE id = " + clinic)).isZero();
        assertThat(count("SELECT count(*) FROM encounter WHERE id = '" + second + "'")).isZero();
        assertThat(count("SELECT count(*) FROM patient_profile WHERE public_id = '" + patient + "'")).isZero();
        mvc.perform(get("/api/v1/admin/doctors/" + id).header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());

        // A doctor with visits still showing at a clinic in use: removed, but not erasable until those visits go
        String ownerEmail = uniqueEmail("liveowner");
        long live = registerClinic("Live Clinic", ownerEmail);
        String owner = login(ownerEmail);
        String locumEmail = uniqueEmail("locum");
        perform(post("/api/v1/clinic/staff"), owner, live, """
                {"name": "Dr Locum", "email": "%s", "password": "%s", "roles": ["DOCTOR"],
                 "doctor": {"registrationNo": "UPMC-9", "registrationCouncil": "UP Medical Council", "qualifications": "MBBS"}}
                """.formatted(locumEmail, PASSWORD)).andExpect(status().isCreated());
        String locum = login(locumEmail);
        String livePatient = body(perform(post("/api/v1/clinic/patients"), owner, live, """
                {"name": "Live Patient", "gender": "M", "ageYears": 50, "phone": "%s"}
                """.formatted(uniquePhone())).andExpect(status().isCreated())).get("publicId").asText();
        visit(locum, live, livePatient);
        long locumId = staffId(locumEmail);
        mvc.perform(post("/api/v1/admin/doctors/" + locumId + "/delete").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"alsoVisits\": false}")).andExpect(status().isOk());
        adminPost("/api/v1/admin/doctors/" + locumId + "/purge", admin).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM clinic WHERE id = " + live)).isEqualTo(1);
    }

    @Test
    void aStraySpaceAfterThePasswordIsForgiven() throws Exception {
        jdbc.sql("DELETE FROM platform_admin").update();
        // A space pasted after the password in the settings box is not part of the password
        new SuperAdminSetup(jdbc, passwords, "space@example.com", ADMIN_PASSWORD + " \n", "Owner").run(null);
        adminLogin("space@example.com", ADMIN_PASSWORD).andExpect(status().isOk());
        // A phone keyboard that adds a space when signing in doesn't make it wrong either
        adminLogin("space@example.com", ADMIN_PASSWORD + " ").andExpect(status().isOk());
        adminLogin("space@example.com", ADMIN_PASSWORD + "x").andExpect(status().isUnauthorized());
    }

    @Test
    void resetSetsThePasswordAgainAndUnlocks() throws Exception {
        jdbc.sql("DELETE FROM platform_admin").update();
        new SuperAdminSetup(jdbc, passwords, "reset@example.com", ADMIN_PASSWORD, "Owner").run(null);
        // Changing the settings later does nothing without the reset switch
        new SuperAdminSetup(jdbc, passwords, "reset@example.com", "a-different-password-1", "Owner").run(null);
        adminLogin("reset@example.com", ADMIN_PASSWORD).andExpect(status().isOk());
        adminLogin("reset@example.com", "a-different-password-1").andExpect(status().isUnauthorized());

        // Locked out by wrong passwords (a good sign-in first clears the one wrong try above)
        adminLogin("reset@example.com", ADMIN_PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 5; i++) {
            adminLogin("reset@example.com", "wrong-password").andExpect(status().isUnauthorized());
        }
        adminLogin("reset@example.com", ADMIN_PASSWORD).andExpect(status().isTooManyRequests());

        // With the reset switch on, that email gets exactly the password in the settings, and is unlocked
        new SuperAdminSetup(jdbc, passwords, "Reset@Example.com", "a-different-password-1", "Owner", true).run(null);
        adminLogin("reset@example.com", "a-different-password-1").andExpect(status().isOk());
        adminLogin("reset@example.com", ADMIN_PASSWORD).andExpect(status().isUnauthorized());
        assertThat(admins()).isEqualTo(1);

        // The switch also adds a new email next to the old one, and refuses a short password
        new SuperAdminSetup(jdbc, passwords, "second@example.com", ADMIN_PASSWORD, "Second", true).run(null);
        assertThat(admins()).isEqualTo(2);
        new SuperAdminSetup(jdbc, passwords, "reset@example.com", "short", "Owner", true).run(null);
        adminLogin("reset@example.com", "a-different-password-1").andExpect(status().isOk());
    }

    private ResultActions adminPost(String path, String token) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private UUID visit(String doctor, long clinic, String patient) throws Exception {
        UUID id = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctor, clinic, """
                {"id": "%s", "patientId": "%s"}
                """.formatted(id, patient)).andExpect(status().isCreated());
        perform(put("/api/v1/clinic/encounters/" + id), doctor, clinic, """
                {"baseVersion": 0, "content": {"chiefComplaints": "Fever", "prescription": [], "investigations": [], "diagnoses": []}}
                """).andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + id + "/sign"), doctor, clinic, null).andExpect(status().isOk());
        return id;
    }

    private ResultActions adminLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder req, String token) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private long staffId(String email) {
        return jdbc.sql("SELECT id FROM staff_user WHERE email = :e").param("e", email).query(Long.class).single();
    }

    private int admins() {
        return jdbc.sql("SELECT count(*) FROM platform_admin").query(Integer.class).single();
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode n : list) {
            if (n.get("id").asLong() == id) return n;
        }
        return null;
    }
}
