package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** The practice patient on the getting-started card: one finished visit, kept out of the counts, removable. */
class SamplePatientTest extends IntegrationTestBase {

    @Test
    void sampleHasAFinishedVisitStaysOutOfCountsAndCanBeRemoved() throws Exception {
        String email = uniqueEmail("sample");
        long clinic = registerClinic("Sample Clinic", email);
        String doctor = login(email);

        String id = body(perform(post("/api/v1/clinic/sample-patient"), doctor, clinic, null)
                .andExpect(status().isOk())).get("publicId").asText();
        // Asking again opens the same one
        assertThat(body(perform(post("/api/v1/clinic/sample-patient"), doctor, clinic, null)
                .andExpect(status().isOk())).get("publicId").asText()).isEqualTo(id);

        JsonNode p = body(perform(get("/api/v1/clinic/patients/" + id), doctor, clinic, null).andExpect(status().isOk()));
        assertThat(p.get("sample").asBoolean()).isTrue();
        assertThat(p.hasNonNull("phone")).isFalse();

        JsonNode visits = body(perform(get("/api/v1/clinic/patients/" + id + "/encounters"), doctor, clinic, null)
                .andExpect(status().isOk()));
        assertThat(visits).hasSize(1);
        assertThat(visits.get(0).get("diagnoses").asText()).isEqualTo("Viral fever");
        assertThat(visits.get(0).get("prescriptionLines").asInt()).isEqualTo(2);

        JsonNode recent = body(perform(get("/api/v1/clinic/patients/recent"), doctor, clinic, null).andExpect(status().isOk()));
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).get("sample").asBoolean()).isTrue();

        for (JsonNode n : body(perform(get("/api/v1/clinic/visit-stats"), doctor, clinic, null).andExpect(status().isOk()))) {
            assertThat(n.get("visits").asInt()).as(n.get("range").asText()).isZero();
        }

        perform(delete("/api/v1/clinic/sample-patient"), doctor, clinic, null).andExpect(status().isNoContent());
        assertThat(body(perform(get("/api/v1/clinic/patients/recent"), doctor, clinic, null).andExpect(status().isOk()))).isEmpty();
        perform(get("/api/v1/clinic/patients/" + id), doctor, clinic, null).andExpect(status().isNotFound());
    }
}
