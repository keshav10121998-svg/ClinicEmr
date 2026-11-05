package com.clinicemr.platform;

import java.util.List;
import java.util.UUID;

import com.clinicemr.platform.PlatformAdminService.DeleteResult;
import com.clinicemr.platform.PlatformAdminService.Doctor;
import com.clinicemr.platform.PlatformAdminService.DoctorDetail;
import com.clinicemr.platform.PlatformAdminService.Overview;
import com.clinicemr.shared.security.AdminContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The super admin's screens: totals across every clinic, every doctor, and removing test doctors and visits. */
@RestController
@RequestMapping("/api/v1/admin")
public class PlatformAdminController {

    private final PlatformAdminService service;
    private final PlatformPurgeService purge;

    public PlatformAdminController(PlatformAdminService service, PlatformPurgeService purge) {
        this.service = service;
        this.purge = purge;
    }

    @GetMapping("/overview")
    public Overview overview(AdminContext admin) {
        return service.overview();
    }

    @GetMapping("/doctors")
    public List<Doctor> doctors(AdminContext admin, @RequestParam(defaultValue = "false") boolean deleted) {
        return service.doctors(deleted);
    }

    @GetMapping("/doctors/{id}")
    public DoctorDetail doctor(AdminContext admin, @PathVariable long id) {
        return service.doctor(id);
    }

    public record DeleteDoctorRequest(Boolean alsoVisits) {
    }

    @PostMapping("/doctors/{id}/delete")
    public DeleteResult deleteDoctor(AdminContext admin, @PathVariable long id, @RequestBody(required = false) DeleteDoctorRequest req) {
        return service.deleteDoctor(admin, id, req == null || req.alsoVisits() == null || req.alsoVisits());
    }

    public record DeleteVisitRequest(String reason) {
    }

    @PostMapping("/visits/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVisit(AdminContext admin, @PathVariable UUID id, @RequestBody(required = false) DeleteVisitRequest req) {
        service.deleteVisit(admin, id, req == null ? null : req.reason());
    }

    /** Erases a removed visit for good. */
    @PostMapping("/visits/{id}/purge")
    public PlatformPurgeService.PurgeResult purgeVisit(AdminContext admin, @PathVariable UUID id) {
        return purge.purgeVisit(admin, id);
    }

    /** Erases a removed doctor for good, with their removed clinics and everything in them. */
    @PostMapping("/doctors/{id}/purge")
    public PlatformPurgeService.PurgeResult purgeDoctor(AdminContext admin, @PathVariable long id) {
        return purge.purgeDoctor(admin, id);
    }
}
