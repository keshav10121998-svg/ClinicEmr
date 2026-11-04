package com.clinicemr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Paid + balance = total; a balance has a due date, and from that day it shows in the doctor's "Money to collect". */
class PaymentTest extends IntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Test
    void recordCollectAndRemind() throws Exception {
        String doctorEmail = uniqueEmail("payadmin");
        long clinic = registerClinic("Pay Clinic", doctorEmail);
        String doctor = login(doctorEmail);
        String doctor2Email = uniqueEmail("paydoc2");
        perform(post("/api/v1/clinic/staff"), doctor, clinic, """
                {"name": "Dr. Second", "email": "%s", "password": "%s", "roles": ["DOCTOR"],
                 "doctor": {"registrationNo": "X-2", "registrationCouncil": "Council", "qualifications": "MBBS"}}
                """.formatted(doctor2Email, PASSWORD)).andExpect(status().isCreated());
        String doctor2 = login(doctor2Email);
        String deskEmail = uniqueEmail("paydesk");
        perform(post("/api/v1/clinic/staff"), doctor, clinic, """
                {"name": "Desk", "email": "%s", "password": "%s", "roles": ["FRONT_DESK"]}
                """.formatted(deskEmail, PASSWORD)).andExpect(status().isCreated());
        String desk = login(deskEmail);

        String anil = patient(doctor, clinic, "Anil Kumar");
        String bina = patient(doctor, clinic, "Bina Rao");
        UUID visitA = visit(doctor, clinic, anil);
        UUID visitB = visit(doctor2, clinic, bina);
        String payA = "/api/v1/clinic/encounters/" + visitA + "/payment";

        // Nothing recorded yet
        perform(get(payA), desk, clinic, null).andExpect(status().isNoContent());

        // Paid in full: total = paid + balance
        perform(put(payA), desk, clinic, "{\"paid\": 300, \"balance\": 0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(300)).andExpect(jsonPath("$.dueDate").doesNotExist());

        // A balance needs a due date that isn't in the past; amounts can't be negative
        perform(put(payA), desk, clinic, "{\"paid\": 300, \"balance\": 150}").andExpect(status().isBadRequest());
        perform(put(payA), desk, clinic, "{\"paid\": 300, \"balance\": 150, \"dueDate\": \"%s\"}".formatted(TODAY.minusDays(1)))
                .andExpect(status().isBadRequest());
        perform(put(payA), desk, clinic, "{\"paid\": -5, \"balance\": 0}").andExpect(status().isBadRequest());
        perform(put(payA), doctor, clinic, "{\"paid\": 300, \"balance\": 150, \"dueDate\": \"%s\"}".formatted(TODAY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(450)).andExpect(jsonPath("$.balance").value(150));

        // Bina owes 200, due in 5 days: not in today's reminder yet, but in the full list and on her page
        perform(put("/api/v1/clinic/encounters/" + visitB + "/payment"), doctor2, clinic,
                "{\"paid\": 100, \"balance\": 200, \"dueDate\": \"%s\"}".formatted(TODAY.plusDays(5))).andExpect(status().isOk());

        JsonNode now = body(perform(get("/api/v1/clinic/payments/due"), desk, clinic, null).andExpect(status().isOk()));
        assertThat(now).hasSize(1);
        assertThat(now.get(0).get("name").asText()).isEqualTo("Anil Kumar");
        assertThat(now.get(0).get("balance").asInt()).isEqualTo(150);
        assertThat(now.get(0).get("overdueDays").asInt()).isZero();
        assertThat(body(perform(get("/api/v1/clinic/payments/due?when=all"), desk, clinic, null))).hasSize(2);
        JsonNode binaDue = body(perform(get("/api/v1/clinic/payments/due?when=all&patientId=" + bina), desk, clinic, null));
        assertThat(binaDue).hasSize(1);
        assertThat(binaDue.get(0).get("overdueDays").asInt()).isEqualTo(-5);

        // Each doctor's reminder shows their own visits
        assertThat(body(perform(get("/api/v1/clinic/payments/due?mine=true"), doctor, clinic, null))).hasSize(1);
        assertThat(body(perform(get("/api/v1/clinic/payments/due?mine=true"), doctor2, clinic, null))).isEmpty();

        // Balance received: it moves into paid and leaves the reminder
        perform(post(payA + "/settle"), desk, clinic, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.paid").value(450)).andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.settledAt").exists());
        assertThat(body(perform(get("/api/v1/clinic/payments/due"), desk, clinic, null))).isEmpty();
        perform(post(payA + "/settle"), desk, clinic, null).andExpect(status().isConflict());

        // Part payments: Bina pays 50 of her 200, the rest stays due on the same date; more than is owed is refused
        String payB = "/api/v1/clinic/encounters/" + visitB + "/payment";
        perform(post(payB + "/receive"), desk, clinic, "{\"amount\": 50}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.paid").value(150)).andExpect(jsonPath("$.balance").value(150))
                .andExpect(jsonPath("$.dueDate").value(TODAY.plusDays(5).toString())).andExpect(jsonPath("$.total").value(300));
        perform(post(payB + "/receive"), desk, clinic, "{\"amount\": 151}").andExpect(status().isBadRequest());
        perform(post(payB + "/receive"), desk, clinic, "{\"amount\": 0}").andExpect(status().isBadRequest());
        perform(post(payB + "/receive"), doctor2, clinic, "{\"amount\": 150}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0)).andExpect(jsonPath("$.dueDate").doesNotExist())
                .andExpect(jsonPath("$.settledAt").exists());
        perform(post(payB + "/receive"), desk, clinic, "{\"amount\": 10}").andExpect(status().isConflict());

        // Another clinic can't see or touch these visits
        String otherEmail = uniqueEmail("payother");
        long other = registerClinic("Other Pay Clinic", otherEmail);
        perform(get(payA), login(otherEmail), other, null).andExpect(status().isNotFound());
    }

    @Test
    void patientLevelPaymentsAndHistory() throws Exception {
        String email = uniqueEmail("patpay");
        long clinic = registerClinic("Patient Pay Clinic", email);
        String doctor = login(email);
        String chetan = patient(doctor, clinic, "Chetan Das");
        String due = TODAY.plusDays(7).toString();

        // Three visits, oldest first, owing 100, 60 and 180 (total 340)
        UUID oldest = visit(doctor, clinic, chetan);
        Thread.sleep(10);
        UUID middle = visit(doctor, clinic, chetan);
        Thread.sleep(10);
        UUID latest = visit(doctor, clinic, chetan);
        for (Object[] v : new Object[][] { { oldest, 200, 100 }, { middle, 300, 60 }, { latest, 120, 180 } }) {
            perform(put("/api/v1/clinic/encounters/" + v[0] + "/payment"), doctor, clinic,
                    "{\"paid\": %s, \"balance\": %s, \"dueDate\": \"%s\"}".formatted(v[1], v[2], due)).andExpect(status().isOk());
        }
        String receive = "/api/v1/clinic/patients/" + chetan + "/payments/receive";

        // Never more than the total balance
        perform(post(receive), doctor, clinic, "{\"amount\": 341}").andExpect(status().isBadRequest());
        perform(post(receive), doctor, clinic, "{\"amount\": 0}").andExpect(status().isBadRequest());

        // 200 is taken off the latest visit first (180), then the next one (20 of its 60)
        JsonNode r = body(perform(post(receive), doctor, clinic, "{\"amount\": 200, \"comment\": \"Paid in cash at the counter\"}")
                .andExpect(status().isOk()));
        assertThat(r.get("remainingBalance").asInt()).isEqualTo(140);
        assertThat(r.get("appliedTo")).hasSize(2);
        assertThat(r.get("appliedTo").get(0).get("encounterId").asText()).isEqualTo(latest.toString());
        assertThat(r.get("appliedTo").get(0).get("amount").asInt()).isEqualTo(180);
        assertThat(r.get("appliedTo").get(1).get("encounterId").asText()).isEqualTo(middle.toString());
        assertThat(r.get("appliedTo").get(1).get("amount").asInt()).isEqualTo(20);
        assertThat(r.get("appliedTo").get(1).get("balanceLeft").asInt()).isEqualTo(40);

        // Fully paid clears the rest: 40 on the middle visit, then 100 on the oldest
        JsonNode all = body(perform(post(receive), doctor, clinic, "{\"full\": true}").andExpect(status().isOk()));
        assertThat(all.get("amount").asInt()).isEqualTo(140);
        assertThat(all.get("remainingBalance").asInt()).isZero();
        perform(post(receive), doctor, clinic, "{\"full\": true}").andExpect(status().isConflict());

        // A correction lowering what was paid at the oldest visit shows as a negative entry
        perform(put("/api/v1/clinic/encounters/" + oldest + "/payment"), doctor, clinic, "{\"paid\": 250, \"balance\": 0}")
                .andExpect(status().isOk());

        // History: totals, and every payment with when, how much and which visit
        JsonNode h = body(perform(get("/api/v1/clinic/patients/" + chetan + "/payments/history"), doctor, clinic, null)
                .andExpect(status().isOk()));
        assertThat(h.get("balance").asInt()).isZero();
        assertThat(h.get("paid").asInt()).isEqualTo(250 + 360 + 300);
        // 3 at the visits + 2 from the 200 payment + 2 from Fully paid + 1 correction
        assertThat(h.get("entries")).hasSize(8);
        assertThat(h.get("entries").get(0).get("kind").asText()).isEqualTo("CORRECTION");
        assertThat(h.get("entries").get(0).get("amount").asInt()).isEqualTo(-50);
        JsonNode batch = h.get("entries").get(1);
        assertThat(batch.get("batchId").isTextual()).isTrue();
        assertThat(h.get("entries").get(2).get("batchId").asText()).isEqualTo(batch.get("batchId").asText());
        assertThat(batch.get("recordedBy").asText()).isEqualTo("Asha Mehta");
        // The 200 payment (older batch) carries its comment on each part
        assertThat(h.get("entries").get(3).get("comment").asText()).isEqualTo("Paid in cash at the counter");
        assertThat(h.get("entries").get(4).get("comment").asText()).isEqualTo("Paid in cash at the counter");
        assertThat(batch.hasNonNull("comment")).isFalse();
    }

    private String patient(String token, long clinic, String name) throws Exception {
        return body(perform(post("/api/v1/clinic/patients"), token, clinic, """
                {"name": "%s", "gender": "M", "ageYears": 40, "phone": "%s"}
                """.formatted(name, uniquePhone())).andExpect(status().isCreated())).get("publicId").asText();
    }

    private UUID visit(String token, long clinic, String patientId) throws Exception {
        UUID id = UUID.randomUUID();
        perform(post("/api/v1/clinic/encounters"), token, clinic, """
                {"id": "%s", "patientId": "%s"}
                """.formatted(id, patientId)).andExpect(status().isCreated());
        return id;
    }
}
