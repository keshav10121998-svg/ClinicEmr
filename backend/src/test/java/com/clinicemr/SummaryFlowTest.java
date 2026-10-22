package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Allergies, patient summary, vitals trends, prescribing warnings, templates and favourites. */
class SummaryFlowTest extends IntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    JdbcClient jdbc;

    @Test
    void summaryTemplatesAndFavourites() throws Exception {
        String doctorEmail = uniqueEmail("asha");
        long clinic = registerClinic("Summary Clinic", doctorEmail);
        String doctor = login(doctorEmail);
        String nurseEmail = uniqueEmail("nurse");
        perform(post("/api/v1/clinic/staff"), doctor, clinic, """
                {"name": "Meena", "email": "%s", "password": "%s", "roles": ["NURSE"]}
                """.formatted(nurseEmail, PASSWORD)).andExpect(status().isCreated());
        String nurse = login(nurseEmail);
        String deskEmail = uniqueEmail("desk");
        perform(post("/api/v1/clinic/staff"), doctor, clinic, """
                {"name": "Ravi", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String desk = login(deskEmail);

        String patientId = body(perform(post("/api/v1/clinic/patients"), desk, clinic, """
                {"name": "Ramesh Kumar", "gender": "M", "dob": "1974-05-10", "phone": "9876500001"}
                """).andExpect(status().isCreated())).get("publicId").asText();
        String patient = "/api/v1/clinic/patients/" + patientId;

        // ----- allergies: not asked yet → none known → a real allergy
        perform(get(patient + "/allergies"), nurse, clinic, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewed").value(false));
        perform(post(patient + "/allergies/none-known"), nurse, clinic, null)
                .andExpect(jsonPath("$.reviewed").value(true)).andExpect(jsonPath("$.noKnownAllergies").value(true));
        perform(post(patient + "/allergies"), nurse, clinic, """
                {"substance": "Penicillin", "reaction": "Rash and swelling", "severity": "SEVERE"}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.noKnownAllergies").value(false))
                .andExpect(jsonPath("$.allergies[0].substance").value("Penicillin"));
        perform(post(patient + "/allergies"), nurse, clinic, "{\"substance\": \"penicillin\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALLERGY_EXISTS"));
        perform(post(patient + "/allergies/none-known"), nurse, clinic, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HAS_ALLERGIES"));

        // ----- templates are personal to doctors
        perform(get("/api/v1/me/templates"), nurse, clinic, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_A_DOCTOR"));
        String templateJson = """
                {"name": "Viral fever", "content": {
                   "chiefComplaints": "Fever, body ache",
                   "advice": "Plenty of fluids. Rest.",
                   "diagnoses": [{"codeSystem": "ICD10", "code": "B34.9", "display": "Viral infection, unspecified"}],
                   "prescription": [{"drugDisplay": "Paracetamol 650 mg tablet", "genericName": "Paracetamol", "dose": "1 tablet",
                                     "frequency": "1-1-1", "timing": "after food", "durationDays": 3}],
                   "investigations": [{"display": "CBC"}],
                   "followUpAfterDays": 3}}
                """;
        String templateId = body(perform(post("/api/v1/me/templates"), doctor, clinic, templateJson)
                .andExpect(status().isCreated())).get("id").asText();
        perform(post("/api/v1/me/templates"), doctor, clinic, templateJson.replace("Viral fever", "viral FEVER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_NAME_TAKEN"));

        // ----- visit 1: apply the template on top of what the doctor typed
        UUID visit1 = startVisit(doctor, clinic, patientId);
        String v1 = "/api/v1/clinic/encounters/" + visit1;
        perform(put(v1), doctor, clinic, """
                {"baseVersion": 0, "content": {"chiefComplaints": "High fever since 2 days"}}
                """).andExpect(status().isOk());
        JsonNode applied = body(perform(post(v1 + "/apply-template"), doctor, clinic, """
                {"baseVersion": 1, "templateId": "%s"}
                """.formatted(templateId)).andExpect(status().isOk()));
        JsonNode c = applied.get("content");
        assertThat(c.get("chiefComplaints").asText()).isEqualTo("High fever since 2 days");   // kept
        assertThat(c.get("advice").asText()).isEqualTo("Plenty of fluids. Rest.");             // filled
        assertThat(c.get("diagnoses").get(0).get("code").asText()).isEqualTo("B34.9");
        assertThat(c.get("followUp").get("dueDate").asText()).isEqualTo(TODAY.plusDays(3).toString());

        // Prescribing amoxicillin to a penicillin-allergic patient, and paracetamol twice, raises warnings
        JsonNode warned = body(perform(put(v1), doctor, clinic, """
                {"baseVersion": 2, "content": {
                   "chiefComplaints": "High fever since 2 days",
                   "vitals": {"bpSystolic": 130, "bpDiastolic": 85, "weightKg": 72},
                   "diagnoses": [{"codeSystem": "ICD10", "code": "B34.9", "display": "Viral infection, unspecified"},
                                 {"codeSystem": "ICD10", "code": "E11.9", "display": "Type 2 diabetes mellitus without complications", "chronic": true}],
                   "prescription": [
                     {"drugDisplay": "Paracetamol 650 mg tablet", "genericName": "Paracetamol", "frequency": "1-1-1", "durationDays": 3},
                     {"drugDisplay": "Amoxicillin 500 mg capsule", "genericName": "Amoxicillin", "frequency": "1-1-1", "durationDays": 5},
                     {"drugDisplay": "Metformin 500 mg tablet", "genericName": "Metformin", "frequency": "1-0-1", "longTerm": true},
                     {"drugDisplay": "Paracetamol 500 mg tablet", "genericName": "Paracetamol", "frequency": "SOS", "durationDays": 2}],
                   "followUp": {"dueDate": "%s"}}}
                """.formatted(TODAY.plusDays(3))).andExpect(status().isOk()));
        List<String> warningTypes = new ArrayList<>();
        warned.get("warnings").forEach(w -> warningTypes.add(w.get("type").asText() + "@" + w.get("line").asInt()));
        assertThat(warningTypes).containsExactlyInAnyOrder("ALLERGY@2", "DUPLICATE_MEDICINE@4");

        // The doctor drops the duplicate and keeps amoxicillin (warnings are advisory), then signs
        perform(put(v1), doctor, clinic, """
                {"baseVersion": 3, "content": {
                   "chiefComplaints": "High fever since 2 days",
                   "vitals": {"bpSystolic": 130, "bpDiastolic": 85, "weightKg": 72},
                   "diagnoses": [{"codeSystem": "ICD10", "code": "B34.9", "display": "Viral infection, unspecified"},
                                 {"codeSystem": "ICD10", "code": "E11.9", "display": "Type 2 diabetes mellitus without complications", "chronic": true}],
                   "prescription": [
                     {"drugDisplay": "Paracetamol 650 mg tablet", "genericName": "Paracetamol", "frequency": "1-1-1", "durationDays": 3},
                     {"drugDisplay": "Amoxicillin 500 mg capsule", "genericName": "Amoxicillin", "frequency": "1-1-1", "durationDays": 5},
                     {"drugDisplay": "Metformin 500 mg tablet", "genericName": "Metformin", "frequency": "1-0-1", "longTerm": true}],
                   "investigations": [{"display": "CBC"}]}}
                """).andExpect(status().isOk());
        perform(post(v1 + "/sign"), doctor, clinic, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SIGNED"));

        // ----- summary: chronic problems only, current medicines, latest vitals, allergies
        JsonNode summary = body(perform(get(patient + "/summary"), nurse, clinic, null).andExpect(status().isOk()));
        assertThat(texts(summary.get("activeProblems"), "code")).containsExactly("E11.9");
        assertThat(texts(summary.get("currentMedicines"), "medicationKey"))
                .containsExactly("metformin", "amoxicillin", "paracetamol");
        assertThat(summary.get("currentMedicines").get(0).has("endsOn")).isFalse();   // long-term: no end date
        assertThat(summary.get("currentMedicines").get(2).get("endsOn").asText()).isEqualTo(TODAY.plusDays(2).toString());
        assertThat(texts(summary.get("latestVitals"), "code")).contains("8480-6", "29463-7");
        assertThat(summary.get("allergies").get("allergies").get(0).get("severity").asText()).isEqualTo("SEVERE");
        assertThat(summary.get("lastVisit").get("encounterId").asText()).isEqualTo(visit1.toString());
        perform(get(patient + "/summary"), desk, clinic, null).andExpect(status().isForbidden());

        // Short courses drop off once finished; long-term medicines stay
        jdbc.sql("UPDATE encounter SET started_at = now() - interval '10 days' WHERE id = :id").param("id", visit1).update();
        summary = body(perform(get(patient + "/summary"), doctor, clinic, null));
        assertThat(texts(summary.get("currentMedicines"), "medicationKey")).containsExactly("metformin");

        // ----- visit 2: follow-up with a new BP reading
        UUID visit2 = startVisit(doctor, clinic, patientId);
        perform(put("/api/v1/clinic/encounters/" + visit2), doctor, clinic, """
                {"baseVersion": 0, "content": {
                   "vitals": {"bpSystolic": 142, "bpDiastolic": 90},
                   "diagnoses": [{"codeSystem": "ICD10", "code": "E11.9", "display": "Type 2 diabetes mellitus without complications", "chronic": true},
                                 {"codeSystem": "ICD10", "code": "I10", "display": "Essential (primary) hypertension", "chronic": true}],
                   "prescription": [{"drugDisplay": "Metformin 500 mg tablet", "genericName": "Metformin", "frequency": "1-0-1", "longTerm": true},
                                    {"drugDisplay": "Amlodipine 5 mg tablet", "genericName": "Amlodipine", "frequency": "1-0-0", "longTerm": true}]}}
                """).andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + visit2 + "/sign"), doctor, clinic, null).andExpect(status().isOk());

        JsonNode trends = body(perform(get(patient + "/vitals?code=8480-6"), doctor, clinic, null).andExpect(status().isOk()));
        assertThat(trends).hasSize(1);
        assertThat(trends.get(0).get("points")).hasSize(2);
        assertThat(trends.get(0).get("points").get(0).get("value").asInt()).isEqualTo(130);   // oldest first
        assertThat(trends.get(0).get("points").get(1).get("value").asInt()).isEqualTo(142);

        // Repeat last prescription comes from the latest visit
        perform(get(patient + "/last-prescription"), doctor, clinic, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encounterId").value(visit2.toString()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].genericName").value("Amlodipine"));

        // ----- doctor decisions on top of the computed summary
        summary = body(perform(post(patient + "/medicines/stop"), doctor, clinic, """
                {"medicationKey": "amlodipine", "reason": "Ankle swelling"}
                """).andExpect(status().isOk()));
        assertThat(texts(summary.get("currentMedicines"), "medicationKey")).containsExactly("metformin");
        summary = body(perform(post(patient + "/problems/resolve"), doctor, clinic, """
                {"problemKey": "I10"}
                """).andExpect(status().isOk()));
        assertThat(texts(summary.get("activeProblems"), "code")).containsExactly("E11.9");
        perform(post(patient + "/problems/resolve"), nurse, clinic, "{\"problemKey\": \"E11.9\"}")
                .andExpect(status().isForbidden());

        // A problem diagnosed again after being resolved becomes active again
        UUID visit3 = startVisit(doctor, clinic, patientId);
        perform(put("/api/v1/clinic/encounters/" + visit3), doctor, clinic, """
                {"baseVersion": 0, "content": {"diagnoses": [{"codeSystem": "ICD10", "code": "I10",
                  "display": "Essential (primary) hypertension", "chronic": true}]}}
                """).andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + visit3 + "/sign"), doctor, clinic, null).andExpect(status().isOk());
        summary = body(perform(get(patient + "/summary"), doctor, clinic, null));
        assertThat(texts(summary.get("activeProblems"), "code")).containsExactlyInAnyOrder("I10", "E11.9");

        // ----- favourites learned from signed visits, plus pinning
        JsonNode dx = body(perform(get("/api/v1/me/favourites?kind=DIAGNOSIS"), doctor, clinic, null).andExpect(status().isOk()));
        // E11.9 and I10 were each used twice; ties go to the most recently used (I10, visit 3)
        assertThat(texts(dx, "ref")).containsExactly("I10", "E11.9", "B34.9");
        assertThat(texts(dx, "useCount")).containsExactly("2", "2", "1");
        JsonNode drugs = body(perform(get("/api/v1/me/favourites?kind=DRUG&q=metf"), doctor, clinic, null));
        assertThat(drugs.get(0).get("entry").get("frequency").asText()).isEqualTo("1-0-1");
        assertThat(drugs.get(0).get("useCount").asInt()).isEqualTo(2);

        perform(post("/api/v1/me/favourites/pin"), doctor, clinic, """
                {"kind": "INVESTIGATION", "investigation": {"code": "4548-4", "display": "HbA1c"}, "pinned": true}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.pinned").value(true));
        JsonNode tests = body(perform(get("/api/v1/me/favourites?kind=INVESTIGATION"), doctor, clinic, null));
        assertThat(tests.get(0).get("ref").asText()).isEqualTo("4548-4");     // pinned beats more used
        assertThat(texts(tests, "ref")).contains("name:cbc");

        // Template use was counted
        perform(get("/api/v1/me/templates"), doctor, clinic, null).andExpect(jsonPath("$[0].useCount").value(1));
    }

    private UUID startVisit(String token, long clinic, String patientId) throws Exception {
        UUID id = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), token, clinic,
                "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(id, patientId)).andExpect(status().isCreated());
        return id;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }
}
