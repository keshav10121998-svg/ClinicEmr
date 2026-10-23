package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

/** The patient app's own records, patient uploads, and allergies from other clinics in prescribing warnings. */
class PatientRecordsTest extends IntegrationTestBase {

    private static final String SULFA_RX = """
            {"drugDisplay": "Cotrimoxazole 960 mg tablet", "genericName": "Sulfamethoxazole + Trimethoprim",
             "frequency": "1-0-1", "durationDays": 5}
            """;

    @Test
    void patientRecordsAndSharedAllergyWarnings() throws Exception {
        when(storage.presignUpload(anyString(), anyString())).thenReturn(URI.create("http://storage/put").toURL());
        when(storage.presignDownload(anyString())).thenReturn(URI.create("http://storage/get").toURL());
        when(storage.sizeOf(anyString())).thenReturn(Optional.of(80_000L));

        String phone = uniquePhone();
        String e164 = "+91" + phone;

        // ----- Clinic A: a signed visit with a private note, and a sulfa allergy
        String emailA = uniqueEmail("a");
        long clinicA = registerClinic("Clinic A", emailA);
        String doctorA = login(emailA);
        String patientA = body(perform(post("/api/v1/clinic/patients"), doctorA, clinicA, """
                {"name": "Sita Rao", "gender": "F", "ageYears": 45, "phone": "%s"}
                """.formatted(phone)).andExpect(status().isCreated())).get("publicId").asText();
        UUID visitA = signVisit(doctorA, clinicA, patientA, """
                {"chiefComplaints": "Burning urine", "privateNote": "Clinic A private note",
                 "diagnoses": [{"code": "N39.0", "display": "Urinary tract infection, site not specified"}],
                 "prescription": [{"drugDisplay": "Nitrofurantoin 100 mg capsule", "genericName": "Nitrofurantoin",
                                   "frequency": "1-0-1", "durationDays": 5}]}
                """);
        perform(post("/api/v1/clinic/patients/" + patientA + "/allergies"), doctorA, clinicA,
                "{\"substance\": \"Sulfa drugs\", \"severity\": \"MODERATE\"}").andExpect(status().isOk());

        // ----- Clinic B links the patient with a visits-only grant
        String emailB = uniqueEmail("b");
        long clinicB = registerClinic("Clinic B", emailB);
        String doctorB = login(emailB);
        String req = body(perform(post("/api/v1/clinic/access-requests"), doctorB, clinicB,
                "{\"phone\": \"%s\"}".formatted(phone))).get("requestId").asText();
        JsonNode verified = body(perform(post("/api/v1/clinic/access-requests/" + req + "/verify"), doctorB, clinicB,
                "{\"otp\": \"%s\"}".formatted(lastOtp(e164))));
        String patientB = verified.get("profiles").get(0).get("profileId").asText();
        perform(post("/api/v1/clinic/access-requests/" + req + "/grant"), doctorB, clinicB, """
                {"profileId": "%s", "scope": {"types": ["VISITS"], "duration": "DAYS_30"}}
                """.formatted(patientB)).andExpect(status().isOk());

        // Prescribing a sulfa drug at clinic B warns about clinic A's allergy record
        UUID visitB = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctorB, clinicB,
                "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(visitB, patientB)).andExpect(status().isCreated());
        String draft = "{\"diagnoses\": [{\"display\": \"Cystitis\"}], \"prescription\": [" + SULFA_RX + "]}";
        JsonNode saved = body(perform(put("/api/v1/clinic/encounters/" + visitB), doctorB, clinicB,
                "{\"baseVersion\": 0, \"content\": " + draft + "}").andExpect(status().isOk()));
        assertThat(saved.get("warnings").get(0).get("type").asText()).isEqualTo("ALLERGY");
        assertThat(saved.get("warnings").get(0).get("source").asText()).isEqualTo("Clinic A");
        assertThat(saved.get("warnings").get(0).get("message").asText()).contains("Sulfa drugs (recorded at Clinic A)");
        // Repeated autosaves are logged once
        perform(put("/api/v1/clinic/encounters/" + visitB), doctorB, clinicB,
                "{\"baseVersion\": 1, \"content\": " + draft + "}").andExpect(status().isOk());

        // ----- Patient app: visits from both clinics, without private notes; prescription PDF
        String token = patientLogin(phone, e164);
        String profile = body(app(get("/api/v1/patient/profiles"), token)).get(0).get("profileId").asText();
        perform(post("/api/v1/clinic/encounters/" + visitB + "/sign"), doctorB, clinicB, null).andExpect(status().isOk());

        JsonNode visits = body(app(get("/api/v1/patient/profiles/" + profile + "/visits"), token).andExpect(status().isOk()));
        assertThat(texts(visits, "clinicName")).containsExactly("Clinic B", "Clinic A");
        JsonNode detail = body(app(get("/api/v1/patient/profiles/" + profile + "/visits/" + visitA), token)
                .andExpect(status().isOk()));
        assertThat(detail.get("content").get("chiefComplaints").asText()).isEqualTo("Burning urine");
        assertThat(detail.get("content").has("privateNote")).isFalse();
        byte[] pdf = app(get("/api/v1/patient/profiles/" + profile + "/visits/" + visitA + "/prescription.pdf"), token)
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        // The shared-allergy check shows in the access log exactly once
        JsonNode log = body(app(get("/api/v1/patient/access-log"), token));
        assertThat(texts(log, "action").stream().filter("CHECK_SHARED_ALLERGIES"::equals)).hasSize(1);

        // ----- Patient uploads an old report: self-uploaded, unverified, deletable
        String docs = "/api/v1/patient/profiles/" + profile + "/documents";
        String docId = body(app(post(docs), token, """
                {"docType": "DISCHARGE_SUMMARY", "title": "2019 hospital discharge", "recordDate": "2019-08-14",
                 "files": [{"mimeType": "image/jpeg", "sizeBytes": 80000}, {"mimeType": "image/jpeg", "sizeBytes": 80000}]}
                """).andExpect(status().isCreated())).get("documentId").asText();
        app(post(docs + "/" + docId + "/complete"), token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PATIENT"))
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.pages").value(2))
                .andExpect(jsonPath("$.canDelete").value(true));
        app(get(docs + "/" + docId), token, null).andExpect(jsonPath("$.pages.length()").value(2));

        // Clinic B shared only visits, so the upload isn't visible there; clinic A can't see it at all
        perform(get("/api/v1/clinic/patients/" + patientB + "/shared-history"), doctorB, clinicB, null)
                .andExpect(jsonPath("$.documents.length()").value(0));
        perform(get("/api/v1/clinic/patients/" + patientA + "/documents"), doctorA, clinicA, null)
                .andExpect(jsonPath("$.length()").value(0));

        app(delete(docs + "/" + docId), token, null).andExpect(status().isNoContent());
        verify(storage, org.mockito.Mockito.times(2)).delete(anyString());
        app(get(docs), token, null).andExpect(jsonPath("$.length()").value(0));

        // ----- After the patient revokes, clinic B no longer gets clinic A's allergy in warnings
        String grantId = body(app(get("/api/v1/patient/access"), token)).get(0).get("grantId").asText();
        app(delete("/api/v1/patient/access/" + grantId), token, null).andExpect(status().isNoContent());
        UUID visitB2 = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctorB, clinicB,
                "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(visitB2, patientB)).andExpect(status().isCreated());
        perform(put("/api/v1/clinic/encounters/" + visitB2), doctorB, clinicB,
                "{\"baseVersion\": 0, \"content\": " + draft + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings.length()").value(0));

        // ----- Another person's account can't see this profile
        String otherPhone = uniquePhone();
        String other = patientLogin(otherPhone, "+91" + otherPhone);
        app(get("/api/v1/patient/profiles/" + profile + "/visits"), other).andExpect(status().isNotFound());
        app(get("/api/v1/patient/profiles/" + profile + "/visits/" + visitA + "/prescription.pdf"), other)
                .andExpect(status().isNotFound());
    }

    @Test
    void clinicDocumentsCantBeDeletedFromTheApp() throws Exception {
        when(storage.presignUpload(anyString(), anyString())).thenReturn(URI.create("http://storage/put").toURL());
        when(storage.sizeOf(anyString())).thenReturn(Optional.of(10_000L));
        String phone = uniquePhone();
        String email = uniqueEmail("c");
        long clinic = registerClinic("Clinic C", email);
        String doctor = login(email);
        String patientId = body(perform(post("/api/v1/clinic/patients"), doctor, clinic, """
                {"name": "Arjun", "gender": "M", "ageYears": 30, "phone": "%s"}
                """.formatted(phone))).get("publicId").asText();
        String docId = body(perform(post("/api/v1/clinic/patients/" + patientId + "/documents"), doctor, clinic, """
                {"docType": "LAB_REPORT", "files": [{"mimeType": "application/pdf", "sizeBytes": 10000}]}
                """)).get("documentId").asText();
        perform(post("/api/v1/clinic/patients/" + patientId + "/documents/" + docId + "/complete"), doctor, clinic, null)
                .andExpect(status().isOk());

        String token = patientLogin(phone, "+91" + phone);
        JsonNode list = body(app(get("/api/v1/patient/profiles/" + patientId + "/documents"), token));
        assertThat(list.get(0).get("clinicName").asText()).isEqualTo("Clinic C");
        assertThat(list.get(0).get("verified").asBoolean()).isTrue();
        assertThat(list.get(0).get("canDelete").asBoolean()).isFalse();
        app(delete("/api/v1/patient/profiles/" + patientId + "/documents/" + docId), token)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLINIC_DOCUMENT"));
    }

    private UUID signVisit(String token, long clinic, String patientId, String content) throws Exception {
        UUID id = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), token, clinic,
                "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(id, patientId)).andExpect(status().isCreated());
        perform(put("/api/v1/clinic/encounters/" + id), token, clinic,
                "{\"baseVersion\": 0, \"content\": " + content + "}").andExpect(status().isOk());
        perform(post("/api/v1/clinic/encounters/" + id + "/sign"), token, clinic, null).andExpect(status().isOk());
        return id;
    }

    private String patientLogin(String phone, String e164) throws Exception {
        String challenge = body(mvc.perform(post("/api/v1/auth/patient/otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\": \"%s\"}".formatted(phone))).andExpect(status().isOk())).get("challengeId").asText();
        return body(mvc.perform(post("/api/v1/auth/patient/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\": \"%s\", \"otp\": \"%s\"}".formatted(challenge, lastOtp(e164))))
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private ResultActions app(MockHttpServletRequestBuilder req, String token) throws Exception {
        return app(req, token, null);
    }

    private ResultActions app(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }
}
