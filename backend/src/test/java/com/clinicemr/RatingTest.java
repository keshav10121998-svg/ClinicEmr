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

/**
 * Patients rate a signed visit from the patient app; the clinic sees the ratings without patient names. Clinics rate
 * ClinicEMR; the super admin sees everything and the public endpoint shows only what clinics allowed.
 */
class RatingTest extends IntegrationTestBase {

    private static final String ADMIN_PASSWORD = "a-long-admin-password";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Test
    void patientsRateVisitsAndClinicsRateTheApp() throws Exception {
        String email = uniqueEmail("rateowner");
        long clinic = registerClinic("Rating Clinic", email);
        String admin = login(email);
        String deskEmail = uniqueEmail("ratedesk");
        perform(post("/api/v1/clinic/staff"), admin, clinic, """
                {"name": "Desk", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String desk = login(deskEmail);
        String doc2Email = uniqueEmail("ratedoc2");
        perform(post("/api/v1/clinic/staff"), admin, clinic, """
                {"name": "Dr. Other", "email": "%s", "password": "%s", "roles": ["DOCTOR"],
                 "doctor": {"registrationNo": "R-2", "registrationCouncil": "Council", "qualifications": "MBBS"}}
                """.formatted(doc2Email, PASSWORD)).andExpect(status().isCreated());
        String doc2 = login(doc2Email);

        String patientId = body(perform(post("/api/v1/clinic/patients"), admin, clinic, """
                {"name": "Rina Kapoor", "gender": "F", "ageYears": 33, "phone": "%s"}
                """.formatted(uniquePhone())).andExpect(status().isCreated())).get("publicId").asText();
        UUID signed = visit(admin, clinic, patientId, true);
        UUID draft = visit(admin, clinic, patientId, false);
        String patient = patientToken(admin, clinic, patientId);
        String base = "/api/v1/patient/profiles/" + patientId;

        // Only a signed visit of this patient's own can be rated, with 1 to 5 stars
        mvc.perform(put(base + "/visits/" + signed + "/rating").contentType(MediaType.APPLICATION_JSON).content("{\"stars\": 5}"))
                .andExpect(status().isUnauthorized());
        rate(patient, base, signed, "{\"stars\": 0}").andExpect(status().isBadRequest());
        rate(patient, base, signed, "{\"stars\": 6}").andExpect(status().isBadRequest());
        rate(patient, base, draft, "{\"stars\": 5}").andExpect(status().isNotFound());
        rate(patient, "/api/v1/patient/profiles/" + UUID.randomUUID(), signed, "{\"stars\": 5}").andExpect(status().isNotFound());
        assertThat(body(app(get(base + "/visits"), patient)).get(0).has("rating")).isFalse();

        // Rating, then changing it: still one rating for the visit
        rate(patient, base, signed, "{\"stars\": 3, \"comment\": \"  Waited long  \"}").andExpect(status().isOk());
        rate(patient, base, signed, "{\"stars\": 5, \"comment\": \"Kind and clear\"}").andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM patient_rating WHERE encounter_id = '" + signed + "'")).isEqualTo(1);
        assertThat(body(app(get(base + "/visits"), patient)).get(0).get("rating").asInt()).isEqualTo(5);
        JsonNode detail = body(app(get(base + "/visits/" + signed), patient));
        assertThat(detail.get("myRating").get("stars").asInt()).isEqualTo(5);
        assertThat(detail.get("myRating").get("comment").asText()).isEqualTo("Kind and clear");

        // The clinic's admin sees the ratings without the patient's name; front desk doesn't see them
        perform(get("/api/v1/clinic/ratings"), desk, clinic, null).andExpect(status().isForbidden());
        ResultActions seen = perform(get("/api/v1/clinic/ratings"), admin, clinic, null).andExpect(status().isOk());
        String raw = seen.andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("Rina");
        JsonNode ratings = body(seen);
        assertThat(ratings.get("mine").asBoolean()).isFalse();
        assertThat(ratings.get("summary").get("average").asDouble()).isEqualTo(5.0);
        assertThat(ratings.get("summary").get("count").asInt()).isEqualTo(1);
        assertThat(ratings.get("summary").get("distribution").get(4).asInt()).isEqualTo(1);
        assertThat(ratings.get("doctors")).hasSize(1);
        assertThat(ratings.get("recent").get(0).get("comment").asText()).isEqualTo("Kind and clear");
        // A doctor who isn't an admin only sees the ratings of their own visits
        JsonNode other = body(perform(get("/api/v1/clinic/ratings"), doc2, clinic, null).andExpect(status().isOk()));
        assertThat(other.get("mine").asBoolean()).isTrue();
        assertThat(other.get("summary").get("count").asInt()).isZero();

        // The clinic rates ClinicEMR: none yet, then one, which can be changed
        perform(get("/api/v1/clinic/app-rating"), admin, clinic, null).andExpect(status().isNoContent());
        perform(put("/api/v1/clinic/app-rating"), desk, clinic, "{\"stars\": 5}").andExpect(status().isForbidden());
        perform(put("/api/v1/clinic/app-rating"), admin, clinic, "{\"stars\": 9}").andExpect(status().isBadRequest());
        perform(put("/api/v1/clinic/app-rating"), admin, clinic, "{\"stars\": 4, \"comment\": \"Good\"}").andExpect(status().isOk());
        JsonNode mine = body(perform(put("/api/v1/clinic/app-rating"), admin, clinic,
                "{\"stars\": 5, \"comment\": \"Saves us time every day\", \"allowPublic\": true}").andExpect(status().isOk()));
        assertThat(mine.get("stars").asInt()).isEqualTo(5);
        assertThat(mine.get("allowPublic").asBoolean()).isTrue();
        assertThat(count("SELECT count(*) FROM app_rating WHERE clinic_id = " + clinic)).isEqualTo(1);

        // A second clinic rates lower and keeps its words private
        String email2 = uniqueEmail("rateowner2");
        long clinic2 = registerClinic("Private Feedback Clinic", email2);
        perform(put("/api/v1/clinic/app-rating"), login(email2), clinic2, "{\"stars\": 3, \"comment\": \"Too many taps\"}").andExpect(status().isOk());

        // Anyone can see the average and only the words clinics allowed
        JsonNode pub = body(mvc.perform(get("/api/v1/public/app-rating")).andExpect(status().isOk()));
        assertThat(pub.get("count").asInt()).isEqualTo(2);
        assertThat(pub.get("average").asDouble()).isEqualTo(4.0);
        assertThat(pub.get("testimonials")).hasSize(1);
        assertThat(pub.get("testimonials").get(0).get("clinicName").asText()).isEqualTo("Rating Clinic");
        assertThat(pub.toString()).doesNotContain("Too many taps");

        // The super admin sees all of it
        String boss = adminToken();
        JsonNode all = body(asAdmin(get("/api/v1/admin/ratings"), boss));
        assertThat(all.get("app").get("count").asInt()).isEqualTo(2);
        assertThat(all.get("appFeedback")).hasSize(2);
        assertThat(all.toString()).contains("Too many taps");
        assertThat(all.get("patients").get("count").asInt()).isEqualTo(1);
        assertThat(all.get("clinics").get(0).get("clinicName").asText()).isEqualTo("Rating Clinic");
        perform(get("/api/v1/admin/ratings"), admin, clinic, null).andExpect(status().isUnauthorized());

        // Removing a visit and deleting it permanently takes its rating with it
        mvc.perform(post("/api/v1/admin/visits/" + signed + "/delete").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + boss).content("{\"reason\": \"test\"}")).andExpect(status().is2xxSuccessful());
        assertThat(body(perform(get("/api/v1/clinic/ratings"), admin, clinic, null)).get("summary").get("count").asInt()).isZero();
        mvc.perform(post("/api/v1/admin/visits/" + signed + "/purge").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + boss).content("{}")).andExpect(status().is2xxSuccessful());
        assertThat(count("SELECT count(*) FROM patient_rating WHERE encounter_id = '" + signed + "'")).isZero();

        // Permanently deleting a doctor who rated the app keeps the clinic's rating (it just forgets who typed it)
        long staff = jdbc.sql("SELECT id FROM staff_user WHERE email = :e").param("e", email).query(Long.class).single();
        mvc.perform(post("/api/v1/admin/doctors/" + staff + "/delete").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + boss).content("{\"alsoVisits\": true}")).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/api/v1/admin/doctors/" + staff + "/purge").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + boss).content("{}")).andExpect(status().is2xxSuccessful());
        assertThat(count("SELECT count(*) FROM app_rating WHERE clinic_id = " + clinic + " AND staff_id IS NULL")).isEqualTo(1);
    }

    private UUID visit(String doctor, long clinic, String patient, boolean sign) throws Exception {
        UUID id = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctor, clinic, """
                {"id": "%s", "patientId": "%s"}
                """.formatted(id, patient)).andExpect(status().isCreated());
        if (sign) {
            perform(put("/api/v1/clinic/encounters/" + id), doctor, clinic, """
                    {"baseVersion": 0, "content": {"chiefComplaints": "Fever", "prescription": [], "investigations": [], "diagnoses": []}}
                    """).andExpect(status().isOk());
            perform(post("/api/v1/clinic/encounters/" + id + "/sign"), doctor, clinic, null).andExpect(status().isOk());
        }
        return id;
    }

    private String patientToken(String staff, long clinic, String patientId) throws Exception {
        JsonNode code = body(perform(post("/api/v1/clinic/patients/" + patientId + "/app-sign-in-code"), staff, clinic, null));
        return body(mvc.perform(post("/api/v1/auth/patient/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\": \"%s\", \"otp\": \"%s\", \"newPin\": \"4826\"}"
                        .formatted(code.get("challengeId").asText(), code.get("code").asText())))
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private ResultActions rate(String token, String base, UUID visit, String json) throws Exception {
        return mvc.perform(put(base + "/visits/" + visit + "/rating").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions app(MockHttpServletRequestBuilder req, String token) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private String adminToken() throws Exception {
        jdbc.sql("DELETE FROM platform_admin").update();
        new SuperAdminSetup(jdbc, passwords, "ratings@example.com", ADMIN_PASSWORD, "Owner").run(null);
        return body(mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"ratings@example.com\", \"password\": \"%s\"}".formatted(ADMIN_PASSWORD)))
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder req, String token) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
