package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Earnings per period (total = paid + balance), money collected, and the list of patients who still owe. */
class EarningsTest extends IntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Test
    void earningsAndWhoOwes() throws Exception {
        String adminEmail = uniqueEmail("earnadmin");
        long clinic = registerClinic("Earn Clinic", adminEmail);
        String admin = login(adminEmail);
        String doc2Email = uniqueEmail("earndoc2");
        perform(post("/api/v1/clinic/staff"), admin, clinic, """
                {"name": "Dr. Two", "email": "%s", "password": "%s", "roles": ["DOCTOR"],
                 "doctor": {"registrationNo": "E-2", "registrationCouncil": "Council", "qualifications": "MBBS"}}
                """.formatted(doc2Email, PASSWORD)).andExpect(status().isCreated());
        String doc2 = login(doc2Email);
        String nurseEmail = uniqueEmail("earnnurse");
        perform(post("/api/v1/clinic/staff"), admin, clinic, """
                {"name": "Nurse", "email": "%s", "password": "%s", "roles": ["NURSE"]}
                """.formatted(nurseEmail, PASSWORD)).andExpect(status().isCreated());

        String due = TODAY.plusDays(3).toString();
        String ravi = patient(admin, clinic, "Ravi Shah");
        String sita = patient(admin, clinic, "Sita Iyer");
        // Admin's visit: 500 charged, 300 paid. Dr. Two's visits: 400 paid in full, and 250 owed by Sita.
        pay(admin, clinic, visit(admin, clinic, ravi), "{\"paid\": 300, \"balance\": 200, \"dueDate\": \"%s\"}".formatted(due));
        pay(doc2, clinic, visit(doc2, clinic, ravi), "{\"paid\": 400, \"balance\": 0}");
        UUID sitaVisit = visit(doc2, clinic, sita);
        pay(doc2, clinic, sitaVisit, "{\"paid\": 0, \"balance\": 250, \"dueDate\": \"%s\"}".formatted(due));

        JsonNode clinicWide = body(perform(get("/api/v1/clinic/earnings"), admin, clinic, null).andExpect(status().isOk()));
        assertThat(clinicWide.get("mine").asBoolean()).isFalse();
        assertThat(clinicWide.get("canSeeClinic").asBoolean()).isTrue();
        for (JsonNode p : clinicWide.get("periods")) {
            assertThat(p.get("visits").asInt()).isEqualTo(3);
            assertThat(p.get("total").asInt()).isEqualTo(1150);
            assertThat(p.get("paid").asInt()).isEqualTo(700);
            assertThat(p.get("balance").asInt()).isEqualTo(450);
            assertThat(p.get("collected").asInt()).isEqualTo(700);
        }

        // A doctor who isn't admin only sees their own visits, even when asking for the clinic's
        JsonNode own = body(perform(get("/api/v1/clinic/earnings?mine=false"), doc2, clinic, null).andExpect(status().isOk()));
        assertThat(own.get("mine").asBoolean()).isTrue();
        assertThat(own.get("canSeeClinic").asBoolean()).isFalse();
        JsonNode today = own.get("periods").get(0);
        assertThat(today.get("range").asText()).isEqualTo("TODAY");
        assertThat(today.get("total").asInt()).isEqualTo(650);
        assertThat(today.get("balance").asInt()).isEqualTo(250);
        JsonNode adminOwn = body(perform(get("/api/v1/clinic/earnings?mine=true"), admin, clinic, null));
        assertThat(adminOwn.get("periods").get(0).get("total").asInt()).isEqualTo(500);

        // Nurses don't see money
        perform(get("/api/v1/clinic/earnings"), login(nurseEmail), clinic, null).andExpect(status().isForbidden());

        // Who owes: Sita 250 and Ravi 200, biggest first on the same due date
        JsonNode owing = body(perform(get("/api/v1/clinic/earnings/owing?range=this_month"), admin, clinic, null)
                .andExpect(status().isOk()));
        assertThat(owing.get("balance").asInt()).isEqualTo(450);
        assertThat(owing.get("patients").asInt()).isEqualTo(2);
        assertThat(owing.get("items").get(0).get("name").asText()).isEqualTo("Sita Iyer");
        assertThat(owing.get("items").get(0).get("balance").asInt()).isEqualTo(250);
        assertThat(owing.get("items").get(0).get("overdueDays").asInt()).isEqualTo(-3);
        assertThat(owing.get("items").get(1).get("name").asText()).isEqualTo("Ravi Shah");
        assertThat(body(perform(get("/api/v1/clinic/earnings/owing"), doc2, clinic, null)).get("items")).hasSize(1);
        perform(get("/api/v1/clinic/earnings/owing?range=week"), admin, clinic, null).andExpect(status().isBadRequest());

        // Sita pays 100 now: collected goes up, her balance goes down
        perform(post("/api/v1/clinic/encounters/" + sitaVisit + "/payment/receive"), admin, clinic, "{\"amount\": 100}")
                .andExpect(status().isOk());
        JsonNode after = body(perform(get("/api/v1/clinic/earnings"), admin, clinic, null)).get("periods").get(0);
        assertThat(after.get("collected").asInt()).isEqualTo(800);
        assertThat(after.get("paid").asInt()).isEqualTo(800);
        assertThat(after.get("balance").asInt()).isEqualTo(350);
        assertThat(after.get("total").asInt()).isEqualTo(1150);

        // Another clinic sees none of it
        String otherEmail = uniqueEmail("earnother");
        long other = registerClinic("Other Earn Clinic", otherEmail);
        JsonNode none = body(perform(get("/api/v1/clinic/earnings"), login(otherEmail), other, null));
        assertThat(none.get("periods").get(3).get("total").asInt()).isZero();
    }

    private void pay(String token, long clinic, UUID visit, String json) throws Exception {
        perform(put("/api/v1/clinic/encounters/" + visit + "/payment"), token, clinic, json).andExpect(status().isOk());
    }

    private String patient(String token, long clinic, String name) throws Exception {
        return body(perform(post("/api/v1/clinic/patients"), token, clinic, """
                {"name": "%s", "gender": "F", "ageYears": 35, "phone": "%s"}
                """.formatted(name, uniquePhone())).andExpect(status().isCreated())).get("publicId").asText();
    }

    private UUID visit(String token, long clinic, String patientId) throws Exception {
        UUID id = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), token, clinic, """
                {"id": "%s", "patientId": "%s"}
                """.formatted(id, patientId)).andExpect(status().isCreated());
        return id;
    }
}
