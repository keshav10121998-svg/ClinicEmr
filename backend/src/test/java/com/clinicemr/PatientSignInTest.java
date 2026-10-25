package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Free patient sign-in: a code shown at the clinic counter, then a PIN for signing in at home. */
class PatientSignInTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Test
    void counterCodeThenPin() throws Exception {
        String adminEmail = uniqueEmail("counter");
        long clinic = registerClinic("Counter Clinic", adminEmail);
        String admin = login(adminEmail);
        String deskEmail = uniqueEmail("counterdesk");
        perform(post("/api/v1/clinic/staff"), admin, clinic, """
                {"name": "Ravi", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String desk = login(deskEmail);

        String phone = uniquePhone();
        String patientId = body(perform(post("/api/v1/clinic/patients"), desk, clinic, """
                {"name": "Geeta Sharma", "gender": "F", "ageYears": 52, "phone": "%s"}
                """.formatted(phone)).andExpect(status().isCreated())).get("publicId").asText();

        // Front desk shows a code; no SMS is sent for it
        JsonNode code = body(perform(post("/api/v1/clinic/patients/" + patientId + "/app-sign-in-code"), desk, clinic, null)
                .andExpect(status().isOk()));
        assertThat(code.get("code").asText()).matches("\\d{6}");
        assertThat(smsTo("+91" + phone)).isEmpty();

        // A newer code replaces the older one
        JsonNode newer = body(perform(post("/api/v1/clinic/patients/" + patientId + "/app-sign-in-code"), desk, clinic, null)
                .andExpect(status().isOk()));
        anon(post("/api/v1/auth/patient/otp/verify"), """
                {"challengeId": "%s", "otp": "%s"}""".formatted(code.get("challengeId").asText(), code.get("code").asText()))
                .andExpect(status().isGone());

        // First sign-in can't finish without choosing a PIN; neither that nor an easy PIN uses up the code
        String typed = newer.get("code").asText();
        anon(post("/api/v1/auth/patient/code/verify"), """
                {"phone": "%s", "otp": "%s"}""".formatted(phone, typed))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PIN_REQUIRED"));
        anon(post("/api/v1/auth/patient/code/verify"), """
                {"phone": "%s", "otp": "%s", "newPin": "1111"}""".formatted(phone, typed)).andExpect(status().isBadRequest());
        anon(post("/api/v1/auth/patient/code/verify"), """
                {"phone": "%s", "otp": "%s", "newPin": "3456"}""".formatted(phone, typed)).andExpect(status().isBadRequest());

        // Code and a good PIN together: signed in with the PIN already set
        JsonNode signedIn = body(anon(post("/api/v1/auth/patient/code/verify"), """
                {"phone": "%s", "otp": "%s", "newPin": "4826"}""".formatted(phone, typed)).andExpect(status().isOk()));
        assertThat(signedIn.get("pinSet").asBoolean()).isTrue();
        assertThat(signedIn.get("profiles").asInt()).isEqualTo(1);
        String pinToken = signedIn.get("accessToken").asText();
        patient(get("/api/v1/patient/account"), pinToken, null).andExpect(jsonPath("$.pinSet").value(true));
        // Single use
        anon(post("/api/v1/auth/patient/code/verify"), """
                {"phone": "%s", "otp": "%s", "newPin": "4826"}""".formatted(phone, typed)).andExpect(status().isGone());

        // At home: mobile number and PIN
        String home = body(anon(post("/api/v1/auth/patient/pin"), """
                {"phone": "%s", "pin": "4826"}""".formatted(phone)).andExpect(status().isOk())).get("accessToken").asText();
        patient(get("/api/v1/patient/profiles"), home, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Geeta Sharma"));

        // Changing the PIN from a PIN sign-in needs the current one
        patient(post("/api/v1/patient/pin"), home, "{\"pin\": \"7391\"}").andExpect(status().isForbidden());
        home = body(patient(post("/api/v1/patient/pin"), home, "{\"pin\": \"7391\", \"currentPin\": \"4826\"}")
                .andExpect(status().isOk())).get("accessToken").asText();

        // Wrong PINs lock the account for a while; even the right PIN waits
        for (int i = 1; i <= 4; i++) {
            anon(post("/api/v1/auth/patient/pin"), "{\"phone\": \"%s\", \"pin\": \"0000\"}".formatted(phone))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.attemptsLeft").value(5 - i));
        }
        anon(post("/api/v1/auth/patient/pin"), "{\"phone\": \"%s\", \"pin\": \"0000\"}".formatted(phone)).andExpect(status().isLocked());
        anon(post("/api/v1/auth/patient/pin"), "{\"phone\": \"%s\", \"pin\": \"7391\"}".formatted(phone)).andExpect(status().isLocked());
        jdbc.sql("UPDATE account SET pin_locked_until = now() - interval '1 minute' WHERE phone_e164 = :p")
                .param("p", "+91" + phone).update();
        anon(post("/api/v1/auth/patient/pin"), "{\"phone\": \"%s\", \"pin\": \"7391\"}".formatted(phone)).andExpect(status().isOk());

        // Unknown numbers get the same answer as a wrong PIN
        anon(post("/api/v1/auth/patient/pin"), "{\"phone\": \"%s\", \"pin\": \"7391\"}".formatted(uniquePhone()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("PIN_INVALID"));

        // Forgot PIN: scanning a fresh counter code sets a new one without the old, and signs out other phones
        String oldPhone = body(anon(post("/api/v1/auth/patient/pin"), "{\"phone\": \"%s\", \"pin\": \"7391\"}".formatted(phone))
                .andExpect(status().isOk())).get("accessToken").asText();
        JsonNode reset = body(perform(post("/api/v1/clinic/patients/" + patientId + "/app-sign-in-code"), desk, clinic, null)
                .andExpect(status().isOk()));
        String renewed = body(anon(post("/api/v1/auth/patient/otp/verify"), """
                {"challengeId": "%s", "otp": "%s", "newPin": "5038"}""".formatted(reset.get("challengeId").asText(), reset.get("code").asText()))
                .andExpect(status().isOk())).get("accessToken").asText();
        patient(get("/api/v1/patient/profiles"), oldPhone, null).andExpect(status().isUnauthorized());
        anon(post("/api/v1/auth/patient/pin"), "{\"phone\": \"%s\", \"pin\": \"5038\"}".formatted(phone)).andExpect(status().isOk());

        // Opening the app swaps the sign-in for a fresh one
        JsonNode fresh = body(patient(post("/api/v1/patient/session/renew"), renewed, null).andExpect(status().isOk()));
        patient(get("/api/v1/patient/profiles"), fresh.get("accessToken").asText(), null).andExpect(status().isOk());
    }

    private ResultActions anon(MockHttpServletRequestBuilder req, String body) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patient(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }
}
