package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Optional;

import com.clinicemr.records.storage.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end API flow against a real PostgreSQL 16 (embedded binaries, no Docker needed).
 * Object storage is mocked; the S3 adapter is exercised against MinIO when running locally.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiFlowTest {

    static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        if (postgres == null) {
            postgres = EmbeddedPostgres.builder().start();
        }
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    ObjectStorage storage;

    @Test
    void clinicStaffPatientsAndDocuments() throws Exception {
        long clinicA = registerClinic("City Care Clinic", "asha@example.com");
        String tokenA = login("asha@example.com");

        // Admin adds a front-desk user, who can sign in and work for the clinic
        perform(post("/api/v1/clinic/staff"), tokenA, clinicA, """
                {"name": "Ravi", "email": "ravi@example.com", "password": "front-desk-pass", "roles": ["FRONT_DESK"]}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.roles[0]").value("FRONT_DESK"));
        String tokenDesk = login("ravi@example.com");

        // Front desk can't add staff
        perform(post("/api/v1/clinic/staff"), tokenDesk, clinicA, """
                {"name": "X", "email": "x@example.com", "password": "xxxxxxxxxxxx", "roles": ["ADMIN"]}
                """).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));

        // Register a patient by age; MRN is assigned per clinic
        JsonNode kamla = body(perform(post("/api/v1/clinic/patients"), tokenDesk, clinicA, """
                {"name": "Kamla Devi", "gender": "F", "ageYears": 76, "phone": "98765 43210"}
                """).andExpect(status().isCreated()));
        assertThat(kamla.get("mrn").asText()).isEqualTo("P000001");
        assertThat(kamla.get("phone").asText()).isEqualTo("+919876543210");
        assertThat(kamla.get("dobEstimated").asBoolean()).isTrue();

        // A near-identical name on the same phone is flagged as a possible duplicate
        perform(post("/api/v1/clinic/patients"), tokenDesk, clinicA, """
                {"name": "Kamla  Devii", "gender": "F", "ageYears": 76, "phone": "+91 98765 43210"}
                """).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POSSIBLE_DUPLICATE"))
                .andExpect(jsonPath("$.candidates[0].mrn").value("P000001"));

        // A family member on the same phone registers fine
        perform(post("/api/v1/clinic/patients"), tokenDesk, clinicA, """
                {"name": "Ramesh Kumar", "gender": "M", "dob": "1974-05-10", "phone": "9876543210"}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.mrn").value("P000002"));

        perform(post("/api/v1/clinic/patients/search-by-phone"), tokenDesk, clinicA, """
                {"phone": "9876543210"}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        // Another clinic can't find or open clinic A's patients
        long clinicB = registerClinic("Other Clinic", "bob@example.com");
        String tokenB = login("bob@example.com");
        perform(post("/api/v1/clinic/patients/search-by-phone"), tokenB, clinicB, """
                {"phone": "9876543210"}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        String kamlaId = kamla.get("publicId").asText();
        perform(get("/api/v1/clinic/patients/" + kamlaId), tokenB, clinicB, null).andExpect(status().isNotFound());

        // Staff can't act for a clinic they don't belong to
        perform(get("/api/v1/clinic/patients/" + kamlaId), tokenB, clinicA, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));

        // Document upload: pre-signed URL, complete, list, view
        when(storage.presignUpload(anyString(), anyString())).thenReturn(URI.create("http://storage/put").toURL());
        when(storage.presignDownload(anyString())).thenReturn(URI.create("http://storage/get").toURL());
        when(storage.sizeOf(anyString())).thenReturn(Optional.of(120_000L));

        JsonNode upload = body(perform(post("/api/v1/clinic/patients/" + kamlaId + "/documents"), tokenDesk, clinicA, """
                {"docType": "LAB_REPORT", "title": "HbA1c", "recordDate": "2026-10-14",
                 "files": [{"mimeType": "application/pdf", "sizeBytes": 120000}]}
                """).andExpect(status().isCreated()));
        String docId = upload.get("documentId").asText();
        assertThat(upload.get("uploads").get(0).get("method").asText()).isEqualTo("PUT");

        perform(post("/api/v1/clinic/patients/" + kamlaId + "/documents/" + docId + "/complete"), tokenDesk, clinicA, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        perform(get("/api/v1/clinic/patients/" + kamlaId + "/documents"), tokenA, clinicA, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].pages").value(1));
        perform(get("/api/v1/clinic/patients/" + kamlaId + "/documents/" + docId), tokenA, clinicA, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.pages[0].url").value("http://storage/get"));

        // Unsupported file types are refused
        perform(post("/api/v1/clinic/patients/" + kamlaId + "/documents"), tokenDesk, clinicA, """
                {"docType": "OTHER", "files": [{"mimeType": "application/x-msdownload", "sizeBytes": 10}]}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void rejectsBadCredentialsAndMissingToken() throws Exception {
        mvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"nobody@example.com\", \"password\": \"whatever-123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(get("/api/v1/clinic")).andExpect(status().isUnauthorized());
    }

    private long registerClinic(String name, String email) throws Exception {
        JsonNode res = body(mvc.perform(post("/api/v1/clinics").contentType(MediaType.APPLICATION_JSON).content("""
                {"clinic": {"name": "%s", "city": "Bengaluru", "pincode": "560038"},
                 "admin": {"name": "Admin", "email": "%s", "password": "a-long-password",
                           "doctor": {"registrationNo": "KMC-1", "registrationCouncil": "Karnataka Medical Council",
                                      "qualifications": "MBBS"}}}
                """.formatted(name, email))).andExpect(status().isCreated()));
        return res.get("clinicId").asLong();
    }

    private String login(String email) throws Exception {
        String password = email.startsWith("ravi") ? "front-desk-pass" : "a-long-password";
        JsonNode res = body(mvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)))
                .andExpect(status().isOk()));
        return res.get("accessToken").asText();
    }

    private ResultActions perform(MockHttpServletRequestBuilder req, String token, long clinicId, String body)
            throws Exception {
        req.header("Authorization", "Bearer " + token).header("X-Clinic-Id", clinicId);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
