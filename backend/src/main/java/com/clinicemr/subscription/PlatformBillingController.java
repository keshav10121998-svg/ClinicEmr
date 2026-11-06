package com.clinicemr.subscription;

import java.util.List;

import com.clinicemr.shared.security.AdminContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The super admin's billing screens: every clinic's plan and payment status, and recording a payment received. */
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
        subscriptions.plan(id);
        return subscriptions.payments(id);
    }

    public record RecordPayment(Integer amount, Integer months, String note) {
    }

    @PostMapping("/clinics/{id}/payments")
    public SubscriptionService.Plan record(AdminContext admin, @PathVariable long id, @RequestBody RecordPayment req) {
        return subscriptions.recordPayment(admin, id, req.amount(), req.months(), req.note());
    }
}
