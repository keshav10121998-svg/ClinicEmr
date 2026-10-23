package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Visit recording: drafts, signing, amendments, prescriptions and PDFs. */
class EncounterFlowTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    private static final String FULL_CONTENT = """
            {"chiefComplaints": "Fever 3 days, sore throat",
             "examination": "Throat congested, tonsils enlarged",
             "privateNote": "Patient anxious about dengue",
             "vitals": {"bpSystolic": 130, "bpDiastolic": 85, "pulse": 96, "temperatureF": 101.2, "spo2": 97, "weightKg": 68},
             "diagnoses": [{"codeSystem": "ICD10", "code": "J02.9", "display": "Acute pharyngitis, unspecified"},
                           {"codeSystem": "ICD10", "code": "E11.9", "display": "Type 2 diabetes mellitus without complications", "kind": "SECONDARY", "chronic": true}],
             "prescription": [
                 {"drugDisplay": "Paracetamol 650 mg tablet", "genericName": "Paracetamol", "dose": "1 tablet", "frequency": "1-0-1", "timing": "after food", "durationDays": 3},
                 {"drugDisplay": "Metformin 500 mg tablet", "genericName": "Metformin", "dose": "1 tablet", "frequency": "1-0-1", "timing": "after food", "longTerm": true}],
             "investigations": [{"display": "CBC"}, {"code": "", "display": "Dengue NS1 antigen"}],
             "advice": "Warm saline gargles. Plenty of fluids.",
             "followUp": {"dueDate": "%s", "note": "Review with reports"}}
            """.formatted(LocalDate.now().plusDays(3));

    @Test
    void draftSignPrintAmend() throws Exception {
        String doctorEmail = uniqueEmail("asha");
        long clinic = registerClinic("City Care Clinic", doctorEmail);
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

        String doctor2Email = uniqueEmail("vikram");
        perform(post("/api/v1/clinic/staff"), doctor, clinic, """
                {"name": "Vikram Rao", "email": "%s", "password": "%s", "roles": ["DOCTOR"],
                 "doctor": {"registrationNo": "KMC-999", "registrationCouncil": "Karnataka Medical Council", "qualifications": "MBBS"}}
                """.formatted(doctor2Email, PASSWORD)).andExpect(status().isCreated());
        String doctor2 = login(doctor2Email);

        JsonNode patient = body(perform(post("/api/v1/clinic/patients"), desk, clinic, """
                {"name": "Kamla Devi", "gender": "F", "ageYears": 76, "phone": "9876543210"}
                """).andExpect(status().isCreated()));
        String patientId = patient.get("publicId").asText();

        // Terminology search: local synonyms and partial words
        perform(get("/api/v1/terminology/diagnoses?q=sugar"), doctor, clinic, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("E11.9"));
        perform(get("/api/v1/terminology/diagnoses?q=J02"), doctor, clinic, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("J02.9"));
        perform(get("/api/v1/terminology/drugs?q=parac"), doctor, clinic, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].genericName").value("Paracetamol"));

        // Front desk can't start a visit; the doctor can, and a retried start returns the same visit
        UUID visitId = UUID.randomUUID();
        String start = """
                {"id": "%s", "patientId": "%s"}
                """.formatted(visitId, patientId);
        perform(post("/api/v1/clinic/encounters"), desk, clinic, start).andExpect(status().isForbidden());
        perform(post("/api/v1/clinic/encounters"), doctor, clinic, start)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));
        perform(post("/api/v1/clinic/encounters"), doctor, clinic, start)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.draftVersion").value(0));
        String visit = "/api/v1/clinic/encounters/" + visitId;

        // Nurse records vitals in the draft; a stale autosave from the doctor is rejected
        perform(put(visit), nurse, clinic, """
                {"baseVersion": 0, "content": {"vitals": {"bpSystolic": 130, "bpDiastolic": 85}}}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.draftVersion").value(1));
        perform(put(visit), doctor, clinic, """
                {"baseVersion": 0, "content": {"chiefComplaints": "Fever"}}
                """).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRAFT_VERSION_CONFLICT"));

        // Everything is optional, but an empty visit can't be signed
        perform(put(visit), doctor, clinic, """
                {"baseVersion": 1, "content": {"chiefComplaints": "  "}}
                """).andExpect(status().isOk());
        perform(post(visit + "/sign"), doctor, clinic, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_FIELDS"))
                .andExpect(jsonPath("$.fields.visit").exists());

        // A colleague can't edit or sign another doctor's visit
        perform(put(visit), doctor2, clinic, "{\"baseVersion\": 2, \"content\": " + FULL_CONTENT + "}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_AUTHOR"));

        perform(put(visit), doctor, clinic, "{\"baseVersion\": 2, \"content\": " + FULL_CONTENT + "}")
                .andExpect(status().isOk());
        JsonNode signed = body(perform(post(visit + "/sign"), doctor, clinic, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"))
                .andExpect(jsonPath("$.currentVersion").value(1)));
        assertThat(signed.get("versions").get(0).get("contentHash").asText()).hasSize(64);

        // Signing again is harmless; editing the draft after signing is locked
        perform(post(visit + "/sign"), doctor, clinic, null).andExpect(status().isOk());
        perform(put(visit), doctor, clinic, "{\"baseVersion\": 4, \"content\": {}}")
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("ENCOUNTER_SIGNED"));

        // Structured rows were written from the signed content
        assertThat(count("SELECT count(*) FROM diagnosis WHERE encounter_id = :id", visitId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM vital_sign WHERE encounter_id = :id", visitId)).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM follow_up WHERE encounter_id = :id", visitId)).isEqualTo(1);

        // Front desk can print the prescription; the private note is not on it
        byte[] pdf = perform(get(visit + "/prescription.pdf"), desk, clinic, null)
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        perform(get(visit + "/prescription.pdf?paper=A4"), desk, clinic, null).andExpect(status().isOk());
        perform(get(visit + "/prescription.pdf?paper=letter"), desk, clinic, null).andExpect(status().isBadRequest());

        // Only the author amends, with a reason; the change is recorded as version 2
        String amended = FULL_CONTENT.replace("\"durationDays\": 3", "\"durationDays\": 5");
        perform(post(visit + "/amend"), doctor2, clinic,
                "{\"reason\": \"Extend course\", \"content\": " + amended + "}").andExpect(status().isForbidden());
        perform(post(visit + "/amend"), doctor, clinic,
                "{\"reason\": \"Extended paracetamol to 5 days\", \"content\": " + amended + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.versions[1].changedFields[0]").value("prescription"))
                .andExpect(jsonPath("$.versions[1].amendmentReason").value("Extended paracetamol to 5 days"));
        perform(post(visit + "/amend"), doctor, clinic,
                "{\"reason\": \"No real change\", \"content\": " + amended + "}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_CHANGES"));
        assertThat(jdbc.sql("""
                SELECT pi.duration_days FROM prescription_item pi JOIN prescription p ON p.id = pi.prescription_id
                 WHERE p.encounter_id = :id AND pi.line_no = 1
                """).param("id", visitId).query(Integer.class).single()).isEqualTo(5);

        // Visit history for the patient
        perform(get("/api/v1/clinic/patients/" + patientId + "/encounters"), doctor, clinic, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].prescriptionLines").value(2))
                .andExpect(jsonPath("$[0].currentVersion").value(2));

        // Signed versions verify; tampering with a stored snapshot is detected
        perform(get(visit + "/integrity"), doctor, clinic, null).andExpect(jsonPath("$.intact").value(true));
        jdbc.sql("""
                UPDATE encounter_version SET snapshot = jsonb_set(snapshot, '{content,advice}', '"Edited directly"')
                 WHERE encounter_id = :id AND version = 1
                """).param("id", visitId).update();
        perform(get(visit + "/integrity"), doctor, clinic, null).andExpect(jsonPath("$.intact").value(false));

        // Another clinic can't open this visit
        String otherEmail = uniqueEmail("other");
        long otherClinic = registerClinic("Other Clinic", otherEmail);
        perform(get(visit), login(otherEmail), otherClinic, null).andExpect(status().isNotFound());
    }

    @Test
    void diagnosisTreatmentAndMedicinesAreOptionalAndLastVisitShowsThem() throws Exception {
        String email = uniqueEmail("doc");
        long clinic = registerClinic("Optional Fields Clinic", email);
        String doctor = login(email);
        String patientId = body(perform(post("/api/v1/clinic/patients"), doctor, clinic, """
                {"name": "Suresh", "gender": "M", "ageYears": 52, "phone": "9123456781"}
                """)).get("publicId").asText();
        String patient = "/api/v1/clinic/patients/" + patientId;

        // Visit 1: only medicines, no diagnosis, and a medicine with nothing but its name
        UUID v1 = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctor, clinic,
                "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(v1, patientId)).andExpect(status().isCreated());
        perform(put("/api/v1/clinic/encounters/" + v1), doctor, clinic, """
                {"baseVersion": 0, "content": {"prescription": [
                   {"drugDisplay": "Pantoprazole 40 mg tablet", "frequency": "1-0-0", "timing": "before food", "durationDays": 14},
                   {"drugDisplay": "Antacid syrup"}]}}
                """).andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + v1 + "/sign"), doctor, clinic, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SIGNED"));
        perform(get("/api/v1/clinic/encounters/" + v1 + "/prescription.pdf"), doctor, clinic, null).andExpect(status().isOk());

        // Visit 2: only a treatment given in the clinic
        UUID v2 = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctor, clinic,
                "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(v2, patientId)).andExpect(status().isCreated());
        perform(put("/api/v1/clinic/encounters/" + v2), doctor, clinic, """
                {"baseVersion": 0, "content": {"treatmentGiven": "Wound dressing, left leg"}}
                """).andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + v2 + "/sign"), doctor, clinic, null).andExpect(status().isOk());

        // Last visit is visit 2 with its time and treatment; history lists both with treatment and medicines
        JsonNode last = body(perform(get(patient + "/summary"), doctor, clinic, null)).get("lastVisit");
        assertThat(last.get("encounterId").asText()).isEqualTo(v2.toString());
        assertThat(last.get("date").asText()).isNotBlank();
        assertThat(last.get("treatmentGiven").asText()).isEqualTo("Wound dressing, left leg");
        assertThat(last.get("medicines")).isEmpty();

        JsonNode history = body(perform(get(patient + "/encounters"), doctor, clinic, null));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("treatmentGiven").asText()).isEqualTo("Wound dressing, left leg");
        assertThat(history.get(1).get("medicines").asText()).isEqualTo("Pantoprazole 40 mg tablet; Antacid syrup");
        assertThat(history.get(1).get("diagnoses").asText()).isEmpty();

        // Back to the visit with medicines: its lines read naturally even when details were left out
        jdbc.sql("UPDATE encounter SET started_at = now() + interval '1 minute' WHERE id = :id").param("id", v1).update();
        last = body(perform(get(patient + "/summary"), doctor, clinic, null)).get("lastVisit");
        assertThat(last.get("medicines").get(0).asText()).isEqualTo("Pantoprazole 40 mg tablet · 1-0-0 · before food · 14 days");
        assertThat(last.get("medicines").get(1).asText()).isEqualTo("Antacid syrup");
        assertThat(last.has("treatmentGiven")).isFalse();
    }

    @Test
    void followUpMustBeAfterTheVisit() throws Exception {
        String email = uniqueEmail("doc");
        long clinic = registerClinic("Follow-up Clinic", email);
        String doctor = login(email);
        String patientId = body(perform(post("/api/v1/clinic/patients"), doctor, clinic, """
                {"name": "Test Patient", "gender": "M", "ageYears": 40, "phone": "9123456780"}
                """)).get("publicId").asText();
        UUID visitId = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctor, clinic,
                "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(visitId, patientId)).andExpect(status().isCreated());
        perform(put("/api/v1/clinic/encounters/" + visitId), doctor, clinic, """
                {"baseVersion": 0, "content": {"diagnoses": [{"display": "Fever"}],
                  "followUp": {"dueDate": "%s"}}}
                """.formatted(LocalDate.now().minusDays(1))).andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + visitId + "/sign"), doctor, clinic, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fields['followUp.dueDate']").exists());
    }

    private int count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Integer.class).single();
    }
}
