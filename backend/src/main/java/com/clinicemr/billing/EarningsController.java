package com.clinicemr.billing;

import com.clinicemr.shared.security.ClinicContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Earnings per period, and who still owes money. {@code mine=true}: only the signed-in doctor's visits (always the
 * case for doctors who aren't admin or front desk).
 */
@RestController
@RequestMapping("/api/v1/clinic/earnings")
public class EarningsController {

    private final EarningsService earnings;

    public EarningsController(EarningsService earnings) {
        this.earnings = earnings;
    }

    @GetMapping
    public EarningsService.Earnings earnings(ClinicContext ctx, @RequestParam(defaultValue = "false") boolean mine) {
        return earnings.earnings(ctx, mine);
    }

    /** {@code range}: today, this_month, this_year or all (default). At most 500 patients. */
    @GetMapping("/owing")
    public EarningsService.OwingList owing(ClinicContext ctx, @RequestParam(defaultValue = "all") String range,
                                           @RequestParam(defaultValue = "false") boolean mine) {
        return earnings.owing(ctx, range, mine);
    }
}
