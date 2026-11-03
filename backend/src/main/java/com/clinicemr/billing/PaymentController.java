package com.clinicemr.billing;

import java.util.List;
import java.util.UUID;

import com.clinicemr.shared.security.ClinicContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** 204 when nothing has been recorded for the visit yet. */
    @GetMapping("/api/v1/clinic/encounters/{id}/payment")
    public ResponseEntity<PaymentService.Payment> get(ClinicContext ctx, @PathVariable UUID id) {
        return payments.get(ctx, id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/api/v1/clinic/encounters/{id}/payment")
    public PaymentService.Payment save(ClinicContext ctx, @PathVariable UUID id, @RequestBody PaymentService.SavePayment req) {
        return payments.save(ctx, id, req);
    }

    @PostMapping("/api/v1/clinic/encounters/{id}/payment/settle")
    public PaymentService.Payment settle(ClinicContext ctx, @PathVariable UUID id) {
        return payments.settle(ctx, id);
    }

    public record Receive(Integer amount) {
    }

    /** Part of the balance received (all of it with the full amount). */
    @PostMapping("/api/v1/clinic/encounters/{id}/payment/receive")
    public PaymentService.Payment receive(ClinicContext ctx, @PathVariable UUID id, @RequestBody Receive req) {
        return payments.receive(ctx, id, req.amount());
    }

    public record ReceiveForPatient(Integer amount, boolean full) {
    }

    /** A payment against the patient's total balance, split across visits latest first; {@code full} pays it all. */
    @PostMapping("/api/v1/clinic/patients/{patientId}/payments/receive")
    public PaymentService.Received receiveForPatient(ClinicContext ctx, @PathVariable UUID patientId,
                                                     @RequestBody ReceiveForPatient req) {
        return payments.receiveForPatient(ctx, patientId, req.amount(), req.full());
    }

    /** What the patient was charged, paid and owes here, and every payment received. */
    @GetMapping("/api/v1/clinic/patients/{patientId}/payments/history")
    public PaymentService.History history(ClinicContext ctx, @PathVariable UUID patientId) {
        return payments.history(ctx, patientId);
    }

    /**
     * Balances to collect. {@code when=now} (default): due today or earlier, the doctor's reminder; {@code all}:
     * every open balance. {@code mine=true}: only the signed-in doctor's visits. {@code patientId}: one patient.
     */
    @GetMapping("/api/v1/clinic/payments/due")
    public List<PaymentService.Due> dues(ClinicContext ctx, @RequestParam(defaultValue = "now") String when,
                                         @RequestParam(defaultValue = "false") boolean mine,
                                         @RequestParam(required = false) UUID patientId) {
        return payments.dues(ctx, !"all".equalsIgnoreCase(when), mine, patientId);
    }
}
