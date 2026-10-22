package com.clinicemr.patient;

import java.util.List;
import java.util.UUID;

import com.clinicemr.patient.PatientDtos.PatientView;
import com.clinicemr.shared.security.ClinicContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clinic/patients")
public class PatientController {

    private final PatientService patients;

    public PatientController(PatientService patients) {
        this.patients = patients;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientView register(ClinicContext ctx, @Valid @RequestBody PatientDtos.RegisterPatientRequest req) {
        return patients.register(ctx, req);
    }

    /** Phone is sent in the body, not the URL, so it doesn't end up in access logs. */
    @PostMapping("/search-by-phone")
    public List<PatientView> byPhone(ClinicContext ctx, @Valid @RequestBody PatientDtos.PhoneSearchRequest req) {
        return patients.findByPhone(ctx, req.phone());
    }

    @GetMapping
    public List<PatientView> search(ClinicContext ctx, @RequestParam("q") String q) {
        return patients.search(ctx, q);
    }

    @GetMapping("/{publicId}")
    public PatientView get(ClinicContext ctx, @PathVariable UUID publicId) {
        return patients.get(ctx, publicId);
    }
}
