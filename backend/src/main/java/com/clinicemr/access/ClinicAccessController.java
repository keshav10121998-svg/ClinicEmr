package com.clinicemr.access;

import java.util.List;
import java.util.UUID;

import com.clinicemr.access.AccessDtos.AccessRequestView;
import com.clinicemr.access.AccessDtos.ClinicGrantRequest;
import com.clinicemr.access.AccessDtos.GrantView;
import com.clinicemr.access.AccessDtos.StartAccessRequest;
import com.clinicemr.access.AccessDtos.VerifyRequest;
import com.clinicemr.patient.PatientService;
import com.clinicemr.shared.security.ClinicContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClinicAccessController {

    private final AccessRequestService requests;
    private final SharedHistoryService shared;
    private final GrantService grants;
    private final PatientService patients;

    public ClinicAccessController(AccessRequestService requests, SharedHistoryService shared, GrantService grants,
                                  PatientService patients) {
        this.requests = requests;
        this.shared = shared;
        this.grants = grants;
        this.patients = patients;
    }

    /** Sends the patient a code (and an in-app prompt). The phone goes in the body, not the URL. */
    @PostMapping("/api/v1/clinic/access-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public AccessRequestView start(ClinicContext ctx, @RequestBody StartAccessRequest req) {
        return requests.start(ctx, req);
    }

    /** Poll this while waiting for an in-app approval. */
    @GetMapping("/api/v1/clinic/access-requests/{id}")
    public AccessRequestView get(ClinicContext ctx, @PathVariable UUID id) {
        return requests.get(ctx, id);
    }

    @PostMapping("/api/v1/clinic/access-requests/{id}/verify")
    public AccessRequestView verify(ClinicContext ctx, @PathVariable UUID id, @Valid @RequestBody VerifyRequest req) {
        return requests.verify(ctx, id, req.otp());
    }

    @PostMapping("/api/v1/clinic/access-requests/{id}/grant")
    public AccessRequestView grant(ClinicContext ctx, @PathVariable UUID id, @Valid @RequestBody ClinicGrantRequest req) {
        return requests.grant(ctx, id, req);
    }

    /** Active grants this clinic holds for the patient. */
    @GetMapping("/api/v1/clinic/patients/{patientId}/access")
    public List<GrantView> access(ClinicContext ctx, @PathVariable UUID patientId) {
        long profileId = patients.requireLinkedProfileId(ctx, patientId);
        return grants.activeForClinicAndProfile(ctx.clinicId(), profileId);
    }

    @GetMapping("/api/v1/clinic/patients/{patientId}/shared-history")
    public SharedHistoryService.SharedHistory sharedHistory(ClinicContext ctx, @PathVariable UUID patientId) {
        return shared.history(ctx, patientId);
    }

    @GetMapping("/api/v1/clinic/patients/{patientId}/shared-history/documents/{documentId}")
    public SharedHistoryService.SharedDocumentLinks sharedDocument(ClinicContext ctx, @PathVariable UUID patientId,
                                                                   @PathVariable UUID documentId) {
        return shared.document(ctx, patientId, documentId);
    }
}
