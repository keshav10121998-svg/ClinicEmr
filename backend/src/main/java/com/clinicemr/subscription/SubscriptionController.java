package com.clinicemr.subscription;

import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Whether the clinic is switched on and until when, for every member of staff. The price is shown to admins only. */
@RestController
@RequestMapping("/api/v1/clinic/plan")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    public SubscriptionService.Plan plan(ClinicContext ctx) {
        return subscriptions.plan(ctx.clinicId(), ctx.has(ClinicRole.ADMIN));
    }
}
