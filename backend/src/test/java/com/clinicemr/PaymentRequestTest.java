package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** A patient sends the amount and a screenshot; the doctor approves (recorded like "Received") or rejects it. */
class PaymentRequestTest extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };

    @Test
    void patientSendsProofDoctorApprovesOrRejects() throws Exception {
        when(storage.presignDownload(anyString())).thenReturn(URI.create("http://files.example/proof").toURL());

        String doctorEmail = uniqueEmail("proofdoc");
        long clinic = registerClinic("Proof Clinic", doctorEmail);
        String doctor = login(doctorEmail);
        String deskEmail = uniqueEmail("proofdesk");
        perform(post("/api/v1/clinic/staff"), doctor, clinic, """
                {"name": "Desk", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String desk = login(deskEmail);

        String phone = uniquePhone();
        String patientId = body(perform(post("/api/v1/clinic/patients"), desk, clinic, """
                {"name": "Meera Iyer", "gender": "F", "ageYears": 35, "phone": "%s"}
                """.formatted(phone)).andExpect(status().isCreated())).get("publicId").asText();
        UUID visit = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), doctor, clinic, "{\"id\": \"%s\", \"patientId\": \"%s\"}".formatted(visit, patientId))
                .andExpect(status().isCreated());
        perform(put("/api/v1/clinic/encounters/" + visit + "/payment"), desk, clinic, "{\"paid\": 100, \"balance\": 300, \"dueDate\": \"%s\"}"
                .formatted(LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(7))).andExpect(status().isOk());

        // The patient signs in and sees what they owe this clinic
        JsonNode code = body(perform(post("/api/v1/clinic/patients/" + patientId + "/app-sign-in-code"), desk, clinic, null));
        String patient = body(mvc.perform(post("/api/v1/auth/patient/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\": \"%s\", \"otp\": \"%s\", \"newPin\": \"4826\"}"
                        .formatted(code.get("challengeId").asText(), code.get("code").asText())))
                .andExpect(status().isOk())).get("accessToken").asText();
        String base = "/api/v1/patient/profiles/" + patientId;
        JsonNode owed = body(app(get(base + "/balances"), patient).andExpect(status().isOk()));
        assertThat(owed).hasSize(1);
        assertThat(owed.get(0).get("balance").asInt()).isEqualTo(300);
        assertThat(owed.get(0).get("clinicName").asText()).isEqualTo("Proof Clinic");

        // Not more than owed, a proof is needed, and it must really be an image or PDF
        send(patient, base, clinic, 400, PNG, "image/png").andExpect(status().isBadRequest());
        send(patient, base, clinic, 200, "not an image".getBytes(), "image/png").andExpect(status().isBadRequest());
        JsonNode first = body(send(patient, base, clinic, 200, PNG, "image/png").andExpect(status().isCreated()));
        assertThat(first.get("status").asText()).isEqualTo("PENDING");

        // Requests still waiting count against what can be claimed: only 100 is left to send proof for
        assertThat(body(app(get(base + "/balances"), patient)).get(0).get("pending").asInt()).isEqualTo(200);
        send(patient, base, clinic, 150, PNG, "image/png").andExpect(status().isBadRequest());
        JsonNode second = body(send(patient, base, clinic, 100, PNG, "image/png").andExpect(status().isCreated()));
        send(patient, base, clinic, 1, PNG, "image/png").andExpect(status().isConflict());

        // The clinic sees both with a link to the screenshot
        JsonNode pending = body(perform(get("/api/v1/clinic/payment-requests"), desk, clinic, null).andExpect(status().isOk()));
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).get("proofUrl").asText()).isEqualTo("http://files.example/proof");
        assertThat(pending.get(0).get("patientName").asText()).isEqualTo("Meera Iyer");

        // Nothing is deleted while the requests are waiting
        verify(storage, never()).delete(anyString());

        // Front desk can't decide; the doctor approves the first: recorded like Received
        String approveFirst = "/api/v1/clinic/payment-requests/" + first.get("id").asText() + "/approve";
        perform(post(approveFirst), desk, clinic, null).andExpect(status().isForbidden());
        perform(post(approveFirst), doctor, clinic, "{\"comment\": \"Received, thank you\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(200)).andExpect(jsonPath("$.remainingBalance").value(100));
        perform(post(approveFirst), doctor, clinic, null).andExpect(status().isConflict());
        // Approving deletes the screenshot: the file is removed and the request keeps no link to it
        verify(storage).delete("payment-proofs/" + first.get("id").asText() + ".png");
        perform(get("/api/v1/clinic/encounters/" + visit + "/payment"), desk, clinic, null)
                .andExpect(jsonPath("$.paid").value(300)).andExpect(jsonPath("$.balance").value(100));

        // The second is rejected with a reason the patient can read
        String rejectSecond = "/api/v1/clinic/payment-requests/" + second.get("id").asText() + "/reject";
        perform(post(rejectSecond), doctor, clinic, "{\"reason\": \"\"}").andExpect(status().isBadRequest());
        perform(post(rejectSecond), doctor, clinic, "{\"reason\": \"Payment not received\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        // Rejecting deletes the screenshot too, and the clinic's list no longer offers it
        verify(storage).delete("payment-proofs/" + second.get("id").asText() + ".png");
        JsonNode decided = body(perform(get("/api/v1/clinic/payment-requests?status=ALL"), desk, clinic, null).andExpect(status().isOk()));
        assertThat(decided).hasSize(2);
        assertThat(decided.get(0).hasNonNull("proofUrl")).isFalse();
        assertThat(decided.get(1).hasNonNull("proofUrl")).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM payment_request WHERE clinic_id = :c AND proof_key IS NULL AND proof_deleted_at IS NOT NULL")
                .param("c", clinic).query(Integer.class).single()).isEqualTo(2);

        JsonNode mine = body(app(get(base + "/payment-requests"), patient).andExpect(status().isOk()));
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).get("status").asText()).isEqualTo("REJECTED");
        assertThat(mine.get(0).get("rejectReason").asText()).isEqualTo("Payment not received");
        assertThat(mine.get(0).hasNonNull("proofUrl")).isFalse();
        assertThat(mine.get(1).get("status").asText()).isEqualTo("APPROVED");
        assertThat(mine.get(1).get("approvedAmount").asInt()).isEqualTo(200);
        assertThat(mine.get(1).get("decisionComment").asText()).isEqualTo("Received, thank you");

        // The approved payment is in the clinic's payment history
        JsonNode history = body(perform(get("/api/v1/clinic/patients/" + patientId + "/payments/history"), desk, clinic, null));
        assertThat(history.get("balance").asInt()).isEqualTo(100);
        assertThat(history.get("entries").get(0).get("amount").asInt()).isEqualTo(200);
        assertThat(history.get("entries").get(0).get("comment").asText()).isEqualTo("Payment proof approved: Received, thank you");
    }

    private ResultActions send(String token, String base, long clinic, int amount, byte[] file, String type) throws Exception {
        return mvc.perform(multipart(base + "/payment-requests")
                .file(new MockMultipartFile("proof", "proof.png", type, file))
                .param("clinicId", String.valueOf(clinic))
                .param("amount", String.valueOf(amount))
                .param("note", "UPI ref 1234")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions app(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String token) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }
}
