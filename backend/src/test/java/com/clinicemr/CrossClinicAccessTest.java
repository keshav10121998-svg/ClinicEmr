package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A patient's history travels to another clinic only with their approval, within scope and time, and is logged. */
class CrossClinicAccessTest extends IntegrationTestBase {

    @Test
    void historyIsSharedOnlyWithPatientApproval() throws Exception {
        when(storage.presignUpload(anyString(), anyString())).thenReturn(URI.create("http://storage/put").toURL());
        when(storage.presignDownload(anyString())).thenReturn(URI.create("http://storage/get").toURL());
        when(storage.sizeOf(anyString())).thenReturn(Optional.of(50_000L));

        String phone = uniquePhone();
        String e164 = "+91" + phone;

        // ----- Clinic A treats the patient: a signed visit with a private note, an allergy, a document
        String emailA = uniqueEmail("clinica");
        long clinicA = registerClinic("Clinic A", emailA);
        String doctorA = login(emailA);
        String kamlaA = body(perform(post("/api/v1/clinic/patients"), doctorA, clinicA, """
                {"name": "Kamla Devi", "gender": "F", "ageYears": 76, "phone": "%s"}
                """.formatted(phone)).andExpect(status().isCreated())).get("publicId").asText();
        UUID visit = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctorA, clinicA,
                "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(visit, kamlaA)).andExpect(status().isCreated());
        perform(put("/api/v1/clinic/encounters/" + visit), doctorA, clinicA, """
                {"baseVersion": 0, "content": {"chiefComplaints": "Routine diabetes review",
                  "privateNote": "Clinic A private note",
                  "diagnoses": [{"code": "E11.9", "display": "Type 2 diabetes mellitus without complications", "chronic": true}],
                  "prescription": [{"drugDisplay": "Metformin 500 mg tablet", "genericName": "Metformin", "frequency": "1-0-1", "longTerm": true}]}}
                """).andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + visit + "/sign"), doctorA, clinicA, null).andExpect(status().isOk());
        perform(post("/api/v1/clinic/patients/" + kamlaA + "/allergies"), doctorA, clinicA,
                "{\"substance\": \"Penicillin\", \"severity\": \"SEVERE\"}").andExpect(status().isOk());
        String docId = body(perform(post("/api/v1/clinic/patients/" + kamlaA + "/documents"), doctorA, clinicA, """
                {"docType": "LAB_REPORT", "title": "HbA1c", "files": [{"mimeType": "application/pdf", "sizeBytes": 50000}]}
                """)).get("documentId").asText();
        perform(post("/api/v1/clinic/patients/" + kamlaA + "/documents/" + docId + "/complete"), doctorA, clinicA, null)
                .andExpect(status().isOk());

        // ----- Clinic B: a doctor and a front desk
        String emailB = uniqueEmail("clinicb");
        long clinicB = registerClinic("Clinic B", emailB);
        String doctorB = login(emailB);
        String deskEmail = uniqueEmail("deskb");
        perform(post("/api/v1/clinic/staff"), doctorB, clinicB, """
                {"name": "Ravi", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String deskB = login(deskEmail);

        // Searching the number at clinic B reveals nothing
        perform(post("/api/v1/clinic/patients/search-by-phone"), deskB, clinicB, "{\"phone\": \"%s\"}".formatted(phone))
                .andExpect(jsonPath("$.length()").value(0));

        // ----- Counter flow: request → OTP → choose person and scope
        JsonNode req = body(perform(post("/api/v1/clinic/access-requests"), deskB, clinicB,
                "{\"phone\": \"%s\"}".formatted(phone)).andExpect(status().isCreated()));
        String reqId = req.get("requestId").asText();
        assertThat(req.get("status").asText()).isEqualTo("PENDING");
        assertThat(req.get("profiles")).isEmpty();
        assertThat(req.get("phone").asText()).isEqualTo("******" + phone.substring(6));
        String code = lastOtp(e164);

        // A colleague can't continue someone else's request
        perform(post("/api/v1/clinic/access-requests/" + reqId + "/verify"), doctorB, clinicB, "{\"otp\": \"%s\"}".formatted(code))
                .andExpect(status().isNotFound());
        perform(post("/api/v1/clinic/access-requests/" + reqId + "/verify"), deskB, clinicB, "{\"otp\": \"%s\"}".formatted(wrong(code)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"))
                .andExpect(jsonPath("$.attemptsLeft").value(4));
        JsonNode verified = body(perform(post("/api/v1/clinic/access-requests/" + reqId + "/verify"), deskB, clinicB,
                "{\"otp\": \"%s\"}".formatted(code)).andExpect(status().isOk()));
        assertThat(verified.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(verified.get("profiles").get(0).get("name").asText()).isEqualTo("Kamla Devi");
        assertThat(verified.get("profiles").get(0).get("linkedToThisClinic").asBoolean()).isFalse();
        String kamla = verified.get("profiles").get(0).get("profileId").asText();

        JsonNode granted = body(perform(post("/api/v1/clinic/access-requests/" + reqId + "/grant"), deskB, clinicB, """
                {"profileId": "%s", "scope": {"types": ["VISITS", "DOCUMENTS"], "duration": "VISIT"}}
                """.formatted(kamla)).andExpect(status().isOk()));
        assertThat(granted.get("grants").get(0).get("mrn").asText()).isEqualTo("P000001");
        assertThat(smsTo(e164).get(smsTo(e164).size() - 1)).contains("Clinic B can now view Kamla Devi's medical records");

        // ----- Clinic B's doctor reads the shared history
        String shared = "/api/v1/clinic/patients/" + kamla + "/shared-history";
        perform(get(shared), deskB, clinicB, null).andExpect(status().isForbidden());
        JsonNode history = body(perform(get(shared), doctorB, clinicB, null).andExpect(status().isOk()));
        JsonNode enc = history.get("encounters").get(0);
        assertThat(enc.get("clinicName").asText()).isEqualTo("Clinic A");
        assertThat(enc.get("content").get("diagnoses").get(0).get("code").asText()).isEqualTo("E11.9");
        assertThat(enc.get("content").has("privateNote")).isFalse();
        assertThat(history.get("allergies").get(0).get("substance").asText()).isEqualTo("Penicillin");
        assertThat(history.get("documents").get(0).get("title").asText()).isEqualTo("HbA1c");
        perform(get(shared + "/documents/" + docId), doctorB, clinicB, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.pages[0].url").value("http://storage/get"));

        // Clinic B's own summary stays its own
        perform(get("/api/v1/clinic/patients/" + kamla + "/summary"), doctorB, clinicB, null)
                .andExpect(jsonPath("$.activeProblems.length()").value(0));

        // ----- Patient app: sign in, see the family, see who looked, revoke
        String challenge = body(mvc.perform(post("/api/v1/auth/patient/otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\": \"%s\"}".formatted(phone))).andExpect(status().isOk())).get("challengeId").asText();
        String patientToken = body(mvc.perform(post("/api/v1/auth/patient/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\": \"%s\", \"otp\": \"%s\"}".formatted(challenge, lastOtp(e164))))
                .andExpect(status().isOk())).get("accessToken").asText();

        patient(get("/api/v1/patient/profiles"), patientToken)
                .andExpect(jsonPath("$[0].name").value("Kamla Devi")).andExpect(jsonPath("$[0].role").value("OWNER"));
        JsonNode access = body(patient(get("/api/v1/patient/access"), patientToken).andExpect(status().isOk()));
        assertThat(access).hasSize(1);
        assertThat(access.get(0).get("clinicName").asText()).isEqualTo("Clinic B");
        JsonNode log = body(patient(get("/api/v1/patient/access-log"), patientToken));
        assertThat(texts(log, "action")).contains("VIEW_SHARED_HISTORY", "VIEW_SHARED_DOCUMENT");
        assertThat(texts(log, "clinicName")).containsOnly("Clinic B");

        patient(delete("/api/v1/patient/access/" + access.get(0).get("grantId").asText()), patientToken)
                .andExpect(status().isNoContent());
        perform(get(shared), doctorB, clinicB, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_RECORD_ACCESS"));

        // ----- In-app approval: clinic B asks again for the now-linked patient; the patient shares visits only
        String req2 = body(perform(post("/api/v1/clinic/access-requests"), doctorB, clinicB,
                "{\"patientId\": \"%s\"}".formatted(kamla)).andExpect(status().isCreated())).get("requestId").asText();
        JsonNode pending = body(patient(get("/api/v1/patient/access-requests"), patientToken));
        assertThat(pending.get(0).get("clinicName").asText()).isEqualTo("Clinic B");
        patient(post("/api/v1/patient/access-requests/" + req2 + "/approve"), patientToken, """
                {"profileIds": ["%s"], "scope": {"types": ["VISITS"], "duration": "DAYS_30"}}
                """.formatted(kamla)).andExpect(status().isOk()).andExpect(jsonPath("$[0].scopeTypes[0]").value("VISITS"));
        perform(get("/api/v1/clinic/access-requests/" + req2), doctorB, clinicB, null)
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.grants.length()").value(1));
        history = body(perform(get(shared), doctorB, clinicB, null).andExpect(status().isOk()));
        assertThat(history.get("encounters")).hasSize(1);
        assertThat(history.get("documents")).isEmpty();
        perform(get(shared + "/documents/" + docId), doctorB, clinicB, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));

        // ----- Tokens can't cross over
        patient(get("/api/v1/clinic/patients/" + kamla), patientToken).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/patient/profiles").header("Authorization", "Bearer " + doctorB))
                .andExpect(status().isUnauthorized());

        // ----- Five wrong codes lock a request for good
        String req3 = body(perform(post("/api/v1/clinic/access-requests"), deskB, clinicB,
                "{\"phone\": \"%s\"}".formatted(phone)).andExpect(status().isCreated())).get("requestId").asText();
        String code3 = lastOtp(e164);
        for (int i = 0; i < 4; i++) {
            perform(post("/api/v1/clinic/access-requests/" + req3 + "/verify"), deskB, clinicB,
                    "{\"otp\": \"%s\"}".formatted(wrong(code3))).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        }
        perform(post("/api/v1/clinic/access-requests/" + req3 + "/verify"), deskB, clinicB,
                "{\"otp\": \"%s\"}".formatted(wrong(code3))).andExpect(jsonPath("$.code").value("OTP_LOCKED"));
        perform(post("/api/v1/clinic/access-requests/" + req3 + "/verify"), deskB, clinicB,
                "{\"otp\": \"%s\"}".formatted(code3)).andExpect(jsonPath("$.status").value("LOCKED"));
    }

    @Test
    void unknownNumbersBehaveLikeKnownOnes() throws Exception {
        String email = uniqueEmail("solo");
        long clinic = registerClinic("Solo Clinic", email);
        String doctor = login(email);
        String phone = uniquePhone();
        JsonNode req = body(perform(post("/api/v1/clinic/access-requests"), doctor, clinic,
                "{\"phone\": \"%s\"}".formatted(phone)).andExpect(status().isCreated()));
        assertThat(req.get("status").asText()).isEqualTo("PENDING");
        JsonNode verified = body(perform(post("/api/v1/clinic/access-requests/" + req.get("requestId").asText() + "/verify"),
                doctor, clinic, "{\"otp\": \"%s\"}".formatted(lastOtp("+91" + phone))).andExpect(status().isOk()));
        assertThat(verified.get("profiles")).isEmpty();
    }

    private ResultActions patient(MockHttpServletRequestBuilder req, String token) throws Exception {
        return patient(req, token, null);
    }

    private ResultActions patient(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }
}
