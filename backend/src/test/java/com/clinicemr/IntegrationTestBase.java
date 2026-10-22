package com.clinicemr;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.clinicemr.records.storage.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
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
 * Shared setup for API tests: one real PostgreSQL 16 (embedded binaries, no Docker) and one Spring context for
 * all test classes. Object storage is mocked. Each test uses unique emails so tests don't interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    static final String PASSWORD = "a-long-password";
    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        synchronized (IntegrationTestBase.class) {
            if (postgres == null) {
                postgres = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        postgres.close();
                    } catch (Exception ignored) {
                        // JVM is exiting anyway
                    }
                }));
            }
        }
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @MockitoBean
    protected ObjectStorage storage;

    protected static String uniqueEmail(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Registers a clinic whose admin is also its doctor; returns the clinic id. */
    protected long registerClinic(String name, String adminEmail) throws Exception {
        JsonNode res = body(mvc.perform(post("/api/v1/clinics").contentType(MediaType.APPLICATION_JSON).content("""
                {"clinic": {"name": "%s", "address": "12 MG Road", "city": "Bengaluru", "state": "Karnataka", "pincode": "560038"},
                 "admin": {"name": "Asha Mehta", "email": "%s", "password": "%s",
                           "doctor": {"registrationNo": "KMC-12345", "registrationCouncil": "Karnataka Medical Council",
                                      "qualifications": "MBBS, MD"}}}
                """.formatted(name, adminEmail, PASSWORD))).andExpect(status().isCreated()));
        return res.get("clinicId").asLong();
    }

    protected String login(String email, String password) throws Exception {
        JsonNode res = body(mvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)))
                .andExpect(status().isOk()));
        return res.get("accessToken").asText();
    }

    protected String login(String email) throws Exception {
        return login(email, PASSWORD);
    }

    protected ResultActions perform(MockHttpServletRequestBuilder req, String token, long clinicId, String body)
            throws Exception {
        req.header("Authorization", "Bearer " + token).header("X-Clinic-Id", clinicId);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
