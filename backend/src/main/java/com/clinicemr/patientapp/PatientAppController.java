package com.clinicemr.patientapp;

import java.util.List;
import java.util.UUID;

import com.clinicemr.access.AccessDtos.AccessLogEntry;
import com.clinicemr.access.AccessDtos.ApproveRequest;
import com.clinicemr.access.AccessDtos.GrantView;
import com.clinicemr.access.AccessDtos.PendingRequest;
import com.clinicemr.shared.security.PatientContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/patient")
public class PatientAppController {

    private final PatientAppService app;

    public PatientAppController(PatientAppService app) {
        this.app = app;
    }

    @GetMapping("/profiles")
    public List<PatientAppService.ProfileView> profiles(PatientContext p) {
        return app.profiles(p);
    }

    @GetMapping("/access-requests")
    public List<PendingRequest> pending(PatientContext p) {
        return app.pendingRequests(p);
    }

    @PostMapping("/access-requests/{id}/approve")
    public List<GrantView> approve(PatientContext p, @PathVariable UUID id, @Valid @RequestBody ApproveRequest req) {
        return app.approve(p, id, req);
    }

    @PostMapping("/access-requests/{id}/deny")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deny(PatientContext p, @PathVariable UUID id) {
        app.deny(p, id);
    }

    /** Clinics that can currently see records of any profile this account manages. */
    @GetMapping("/access")
    public List<GrantView> access(PatientContext p) {
        return app.activeGrants(p);
    }

    @DeleteMapping("/access/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(PatientContext p, @PathVariable UUID grantId) {
        app.revoke(p, grantId);
    }

    @GetMapping("/access-log")
    public List<AccessLogEntry> accessLog(PatientContext p, @RequestParam(required = false) UUID profileId) {
        return app.accessLog(p, profileId);
    }
}
