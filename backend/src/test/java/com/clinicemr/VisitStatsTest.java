package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Patients seen today, yesterday, in the last 7 days, this year and in total, and the list behind each count. */
class VisitStatsTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Test
    void countsAndListsPerPeriod() throws Exception {
        String doctorEmail = uniqueEmail("stats");
        long clinic = registerClinic("Stats Clinic", doctorEmail);
        String doctor = login(doctorEmail);
        String deskEmail = uniqueEmail("statsdesk");
        perform(post("/api/v1/clinic/staff"), doctor, clinic, """
                {"name": "Ravi", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String desk = login(deskEmail);

        String a = patient(doctor, clinic, "Anil Kumar");
        String b = patient(doctor, clinic, "Bina Rao");
        String c = patient(doctor, clinic, "Chetan Das");

        startVisit(doctor, clinic, a);                       // today
        startVisit(doctor, clinic, a);                       // today again: one patient, two visits
        UUID signed = startVisit(doctor, clinic, b);          // today, finished with a diagnosis
        perform(put("/api/v1/clinic/encounters/" + signed), doctor, clinic, """
                {"baseVersion": 0, "content": {"diagnoses": [{"display": "Viral fever"}], "prescription": [], "investigations": []}}
                """).andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + signed + "/sign"), doctor, clinic, null).andExpect(status().isOk());
        moveBack(startVisit(doctor, clinic, c), "1 day");   // yesterday
        moveBack(startVisit(doctor, clinic, a), "10 days"); // outside the last 7 days
        UUID cancelled = startVisit(doctor, clinic, c);      // cancelled visits don't count
        perform(post("/api/v1/clinic/encounters/" + cancelled + "/cancel"), doctor, clinic, """
                {"reason": "Opened by mistake"}
                """).andExpect(status().isOk());

        // Another clinic's visits never show up here
        String otherEmail = uniqueEmail("other");
        long otherClinic = registerClinic("Other Clinic", otherEmail);
        String other = login(otherEmail);
        startVisit(other, otherClinic, patient(other, otherClinic, "Someone Else"));

        Map<String, int[]> counts = new HashMap<>();
        for (JsonNode n : body(perform(get("/api/v1/clinic/visit-stats"), desk, clinic, null).andExpect(status().isOk()))) {
            counts.put(n.get("range").asText(), new int[] { n.get("patients").asInt(), n.get("visits").asInt() });
        }
        assertThat(counts.get("TODAY")).containsExactly(2, 3);
        assertThat(counts.get("YESTERDAY")).containsExactly(1, 1);
        assertThat(counts.get("LAST_7_DAYS")).containsExactly(3, 4);
        assertThat(counts.get("ALL")).containsExactly(3, 5);
        assertThat(counts.get("THIS_YEAR")[1]).isBetween(4, 5); // the 10-day-old visit may fall in last year

        // The list behind "today": newest first; front desk sees who came, not the diagnosis
        JsonNode today = body(perform(get("/api/v1/clinic/visit-stats/visitors?range=today"), desk, clinic, null)
                .andExpect(status().isOk()));
        assertThat(today.get("patients").asInt()).isEqualTo(2);
        assertThat(today.get("items")).hasSize(3);
        assertThat(today.get("items").get(0).get("name").asText()).isEqualTo("Bina Rao");
        assertThat(today.get("items").get(0).hasNonNull("diagnoses")).isFalse();

        JsonNode forDoctor = body(perform(get("/api/v1/clinic/visit-stats/visitors?range=today"), doctor, clinic, null)
                .andExpect(status().isOk()));
        assertThat(forDoctor.get("items").get(0).get("diagnoses").asText()).isEqualTo("Viral fever");
        assertThat(forDoctor.get("items").get(0).get("status").asText()).isEqualTo("SIGNED");
        // Each row carries the patient's finished visits at this clinic, as does the patient list
        assertThat(forDoctor.get("items").get(0).get("visitCount").asInt()).isEqualTo(1);
        assertThat(forDoctor.get("items").get(1).get("visitCount").asInt()).isZero();
        JsonNode recent = body(perform(get("/api/v1/clinic/patients/recent"), desk, clinic, null).andExpect(status().isOk()));
        for (JsonNode p : recent) {
            assertThat(p.get("visitCount").asInt()).isEqualTo(p.get("name").asText().equals("Bina Rao") ? 1 : 0);
        }

        JsonNode yesterday = body(perform(get("/api/v1/clinic/visit-stats/visitors?range=yesterday"), doctor, clinic, null)
                .andExpect(status().isOk()));
        assertThat(yesterday.get("items")).hasSize(1);
        assertThat(yesterday.get("items").get(0).get("name").asText()).isEqualTo("Chetan Das");

        perform(get("/api/v1/clinic/visit-stats/visitors?range=fortnight"), doctor, clinic, null)
                .andExpect(status().isBadRequest());
    }

    private String patient(String token, long clinic, String name) throws Exception {
        return body(perform(post("/api/v1/clinic/patients"), token, clinic, """
                {"name": "%s", "gender": "M", "ageYears": 40, "phone": "%s"}
                """.formatted(name, uniquePhone())).andExpect(status().isCreated())).get("publicId").asText();
    }

    private UUID startVisit(String token, long clinic, String patientId) throws Exception {
        UUID id = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), token, clinic, """
                {"id": "%s", "patientId": "%s"}
                """.formatted(id, patientId)).andExpect(status().isCreated());
        Thread.sleep(5); // keep start times distinct so "newest first" is well defined
        return id;
    }

    private void moveBack(UUID encounterId, String interval) {
        jdbc.sql("UPDATE encounter SET started_at = started_at - CAST(:i AS interval) WHERE id = :id")
                .param("i", interval).param("id", encounterId).update();
    }
}
