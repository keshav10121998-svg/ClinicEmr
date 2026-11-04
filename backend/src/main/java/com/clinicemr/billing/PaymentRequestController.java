package com.clinicemr.billing;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.PatientContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Patients send proof of a payment they made; the clinic approves or rejects it. */
@RestController
public class PaymentRequestController {

    private final PaymentRequestService requests;

    public PaymentRequestController(PaymentRequestService requests) {
        this.requests = requests;
    }

    // ----- patient app

    /** What this person owes each clinic, and how much is already waiting for approval. */
    @GetMapping("/api/v1/patient/profiles/{profileId}/balances")
    public List<PaymentRequestService.ClinicBalance> balances(PatientContext p, @PathVariable UUID profileId) {
        return requests.balances(p, profileId);
    }

    /** Multipart: clinicId, amount, optional note, and proof (screenshot or PDF). */
    @PostMapping(value = "/api/v1/patient/profiles/{profileId}/payment-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentRequestService.Request submit(PatientContext p, @PathVariable UUID profileId,
                                                @RequestParam long clinicId, @RequestParam Integer amount,
                                                @RequestParam(required = false) String note,
                                                @RequestPart("proof") MultipartFile proof) throws IOException {
        return requests.submit(p, profileId, clinicId, amount, note, proof);
    }

    @GetMapping("/api/v1/patient/profiles/{profileId}/payment-requests")
    public List<PaymentRequestService.Request> mine(PatientContext p, @PathVariable UUID profileId) {
        return requests.forPatient(p, profileId);
    }

    // ----- clinic

    /** {@code status}: PENDING (default), APPROVED, REJECTED or ALL; {@code patientId}: one patient's. */
    @GetMapping("/api/v1/clinic/payment-requests")
    public List<PaymentRequestService.Request> forClinic(ClinicContext ctx, @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) UUID patientId) {
        return requests.forClinic(ctx, status, patientId);
    }

    public record Approve(String comment) {
    }

    /** {@code comment} (optional) is shown to the patient and saved in the payment history. */
    @PostMapping("/api/v1/clinic/payment-requests/{id}/approve")
    public PaymentService.Received approve(ClinicContext ctx, @PathVariable UUID id, @RequestBody(required = false) Approve req) {
        return requests.approve(ctx, id, req == null ? null : req.comment());
    }

    public record Reject(String reason) {
    }

    @PostMapping("/api/v1/clinic/payment-requests/{id}/reject")
    public PaymentRequestService.Request reject(ClinicContext ctx, @PathVariable UUID id, @RequestBody Reject req) {
        return requests.reject(ctx, id, req.reason());
    }
}
