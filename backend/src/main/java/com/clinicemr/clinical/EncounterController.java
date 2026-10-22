package com.clinicemr.clinical;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.clinical.EncounterDtos.AmendRequest;
import com.clinicemr.clinical.EncounterDtos.EncounterListItem;
import com.clinicemr.clinical.EncounterDtos.EncounterView;
import com.clinicemr.clinical.EncounterDtos.SaveDraftRequest;
import com.clinicemr.clinical.EncounterDtos.StartEncounterRequest;
import com.clinicemr.shared.security.ClinicContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EncounterController {

    private final EncounterService encounters;

    public EncounterController(EncounterService encounters) {
        this.encounters = encounters;
    }

    @PostMapping("/api/v1/clinic/encounters")
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterView start(ClinicContext ctx, @Valid @RequestBody StartEncounterRequest req) {
        return encounters.start(ctx, req);
    }

    @GetMapping("/api/v1/clinic/encounters/{id}")
    public EncounterView get(ClinicContext ctx, @PathVariable UUID id) {
        return encounters.get(ctx, id);
    }

    /** Autosave of the draft. Not validated until signing, so half-typed entries are kept. */
    @PutMapping("/api/v1/clinic/encounters/{id}")
    public EncounterView saveDraft(ClinicContext ctx, @PathVariable UUID id, @RequestBody SaveDraftRequest req) {
        return encounters.saveDraft(ctx, id, req);
    }

    @PostMapping("/api/v1/clinic/encounters/{id}/apply-template")
    public EncounterView applyTemplate(ClinicContext ctx, @PathVariable UUID id,
                                       @Valid @RequestBody EncounterDtos.ApplyTemplateRequest req) {
        return encounters.applyTemplate(ctx, id, req);
    }

    @PostMapping("/api/v1/clinic/encounters/{id}/sign")
    public EncounterView sign(ClinicContext ctx, @PathVariable UUID id) {
        return encounters.sign(ctx, id);
    }

    @PostMapping("/api/v1/clinic/encounters/{id}/amend")
    public EncounterView amend(ClinicContext ctx, @PathVariable UUID id, @Valid @RequestBody AmendRequest req) {
        return encounters.amend(ctx, id, req);
    }

    @PostMapping("/api/v1/clinic/encounters/{id}/cancel")
    public EncounterView cancel(ClinicContext ctx, @PathVariable UUID id) {
        return encounters.cancel(ctx, id);
    }

    @GetMapping("/api/v1/clinic/encounters/{id}/integrity")
    public Map<String, Boolean> integrity(ClinicContext ctx, @PathVariable UUID id) {
        return Map.of("intact", encounters.verifyIntegrity(ctx, id));
    }

    @GetMapping("/api/v1/clinic/patients/{patientId}/encounters")
    public List<EncounterListItem> forPatient(ClinicContext ctx, @PathVariable UUID patientId) {
        return encounters.listForPatient(ctx, patientId);
    }
}
