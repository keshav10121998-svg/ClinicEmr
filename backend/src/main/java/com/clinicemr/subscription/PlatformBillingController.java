package com.clinicemr.subscription;

import java.util.List;

import com.clinicemr.shared.security.AdminContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The super admin's billing screens: every clinic's access, switching a clinic on for some months (free or at the
 * agreed price, with the money received), and switching it off.
 */
@RestController
@RequestMapping("/api/v1/admin/billing")
public class PlatformBillingController {

    private final SubscriptionService subscriptions;

    public PlatformBillingController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    public SubscriptionService.Overview overview(AdminContext admin) {
        return subscriptions.overview();
    }

    @GetMapping("/clinics/{id}/payments")
    public List<SubscriptionService.Payment> payments(AdminContext admin, @PathVariable long id) {
        subscriptions.plan(id, true);
        return subscriptions.payments(id);
    }

    @PostMapping("/clinics/{id}/grant")
    public SubscriptionService.Plan grant(AdminContext admin, @PathVariable long id, @RequestBody SubscriptionService.Grant req) {
        return subscriptions.grant(admin, id, req);
    }

    @PostMapping("/clinics/{id}/switch-off")
    public SubscriptionService.Plan switchOff(AdminContext admin, @PathVariable long id) {
        return subscriptions.switchOff(admin, id);
    }
}
