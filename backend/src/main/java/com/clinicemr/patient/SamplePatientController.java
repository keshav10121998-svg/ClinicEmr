package com.clinicemr.patient;

import java.util.Map;
import java.util.UUID;

import com.clinicemr.shared.security.ClinicContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The practice patient on the getting-started card: open it (made the first time) or remove it. */
@RestController
@RequestMapping("/api/v1/clinic/sample-patient")
public class SamplePatientController {

    private final SamplePatientService samples;

    public SamplePatientController(SamplePatientService samples) {
        this.samples = samples;
    }

    @PostMapping
    public Map<String, UUID> open(ClinicContext ctx) {
        return Map.of("publicId", samples.open(ctx));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(ClinicContext ctx) {
        samples.remove(ctx);
    }
}
