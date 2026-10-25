package com.clinicemr.clinic;

import java.util.List;

import com.clinicemr.shared.security.ClinicContext;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClinicController {

    private final ClinicService clinics;

    public ClinicController(ClinicService clinics) {
        this.clinics = clinics;
    }

    @PostMapping("/api/v1/clinics")
    @ResponseStatus(HttpStatus.CREATED)
    public ClinicDtos.RegisterClinicResponse register(@Valid @RequestBody ClinicDtos.RegisterClinicRequest req) {
        try {
            long id = clinics.registerClinic(req);
            return new ClinicDtos.RegisterClinicResponse(id, "PENDING",
                    "Sign in with the admin email. Your clinic can be used right away and will be verified by our team.");
        } catch (DuplicateKeyException e) {
            throw ClinicService.emailTaken();
        }
    }

    @GetMapping("/api/v1/clinic")
    public ClinicService.ClinicView current(ClinicContext ctx) {
        return clinics.get(ctx);
    }

    @GetMapping("/api/v1/clinic/staff")
    public List<ClinicService.StaffView> staff(ClinicContext ctx) {
        return clinics.listStaff(ctx);
    }

    @PostMapping("/api/v1/clinic/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public ClinicService.StaffView addStaff(ClinicContext ctx, @Valid @RequestBody ClinicDtos.AddStaffRequest req) {
        return clinics.addStaff(ctx, req);
    }

    public record SetPhoneRequest(String phone) {
    }

    /** Admins set or remove (blank) a staff member's mobile number, which they can then sign in with. */
    @PutMapping("/api/v1/clinic/staff/{staffId}/phone")
    public ClinicService.StaffView setStaffPhone(ClinicContext ctx, @PathVariable long staffId, @RequestBody SetPhoneRequest req) {
        return clinics.setStaffPhone(ctx, staffId, req.phone());
    }
}
