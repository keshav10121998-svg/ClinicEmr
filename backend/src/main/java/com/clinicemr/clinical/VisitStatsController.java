package com.clinicemr.clinical;

import java.util.List;

import com.clinicemr.shared.security.ClinicContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Patients seen by this clinic: counts per period, and the list for one period. Any clinic role can see these. */
@RestController
@RequestMapping("/api/v1/clinic/visit-stats")
public class VisitStatsController {

    private final VisitStatsService stats;

    public VisitStatsController(VisitStatsService stats) {
        this.stats = stats;
    }

    @GetMapping
    public List<VisitStatsService.Count> counts(ClinicContext ctx) {
        return stats.counts(ctx);
    }

    /** {@code range}: today, yesterday, last_7_days, this_year or all. Newest first, at most 500. */
    @GetMapping("/visitors")
    public VisitStatsService.VisitorList visitors(ClinicContext ctx, @RequestParam(defaultValue = "today") String range) {
        return stats.visitors(ctx, range);
    }
}
