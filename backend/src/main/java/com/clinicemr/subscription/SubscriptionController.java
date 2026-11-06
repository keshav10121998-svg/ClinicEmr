package com.clinicemr.subscription;

import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A clinic's own plan: this month's price, what it includes, and when the price changes. Clinic admins only. */
@RestController
@RequestMapping("/api/v1/clinic/plan")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    public SubscriptionService.Plan plan(ClinicContext ctx) {
        ctx.require(ClinicRole.ADMIN);
        return subscriptions.plan(ctx.clinicId());
    }
}
