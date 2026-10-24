package com.clinicemr.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.clinicemr.clinical.VisitStatsService.Range;
import org.junit.jupiter.api.Test;

/** Periods follow India-time calendar days, not UTC days. */
class VisitStatsWindowTest {

    // 00:30 on 24 Oct in India is still 23 Oct in UTC
    private final VisitStatsService stats = new VisitStatsService(null,
            Clock.fixed(Instant.parse("2026-10-23T19:00:00Z"), ZoneOffset.UTC));

    @Test
    void daysStartAtIndianMidnight() {
        assertThat(stats.window(Range.TODAY))
                .containsExactly(Instant.parse("2026-10-23T18:30:00Z"), Instant.parse("2026-10-24T18:30:00Z"));
        assertThat(stats.window(Range.YESTERDAY))
                .containsExactly(Instant.parse("2026-10-22T18:30:00Z"), Instant.parse("2026-10-23T18:30:00Z"));
        assertThat(stats.window(Range.LAST_7_DAYS)[0]).isEqualTo(Instant.parse("2026-10-17T18:30:00Z"));
        assertThat(stats.window(Range.THIS_YEAR)[0]).isEqualTo(Instant.parse("2025-12-31T18:30:00Z"));
    }
}
