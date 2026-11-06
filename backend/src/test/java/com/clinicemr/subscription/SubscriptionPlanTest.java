package com.clinicemr.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import com.clinicemr.subscription.SubscriptionPlan.Stage;
import com.clinicemr.subscription.SubscriptionService.Plan;
import com.clinicemr.subscription.SubscriptionService.Status;
import org.junit.jupiter.api.Test;

/** The price list: a free month, six months at 50, then 100, and 20 for each staff member after the owner. */
class SubscriptionPlanTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 10);

    @Test
    void stagesFollowTheMonths() {
        assertThat(SubscriptionPlan.monthNumber(START, START)).isEqualTo(1);
        assertThat(SubscriptionPlan.monthNumber(START, LocalDate.of(2026, 2, 9))).isEqualTo(1);
        assertThat(SubscriptionPlan.monthNumber(START, LocalDate.of(2026, 2, 10))).isEqualTo(2);
        assertThat(SubscriptionPlan.stage(1)).isEqualTo(Stage.FREE_MONTH);
        assertThat(SubscriptionPlan.stage(2)).isEqualTo(Stage.LAUNCH);
        assertThat(SubscriptionPlan.stage(7)).isEqualTo(Stage.LAUNCH);
        assertThat(SubscriptionPlan.stage(8)).isEqualTo(Stage.STANDARD);
        assertThat(SubscriptionPlan.standardFrom(START)).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    void freeMonthIsFreeEvenWithStaff() {
        Plan p = SubscriptionService.build(1, "A", START, null, 4, LocalDate.of(2026, 1, 20));
        assertThat(p.status()).isEqualTo(Status.FREE_MONTH);
        assertThat(p.total()).isZero();
        assertThat(p.billableStaff()).isEqualTo(3);
        assertThat(p.nextChange().on()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(p.nextChange().basePrice()).isEqualTo(50);
        // What it pays to continue after the free month: 50 + 3 staff at 20
        assertThat(p.nextAmount()).isEqualTo(110);
    }

    @Test
    void launchPriceThenStandardPrice() {
        Plan launch = SubscriptionService.build(1, "A", START, LocalDate.of(2026, 4, 10), 3, LocalDate.of(2026, 3, 1));
        assertThat(launch.status()).isEqualTo(Status.PAID);
        assertThat(launch.monthNumber()).isEqualTo(2);
        assertThat(launch.total()).isEqualTo(50 + 2 * 20);
        assertThat(launch.nextChange().on()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(launch.nextChange().basePrice()).isEqualTo(100);
        assertThat(launch.daysLeft()).isEqualTo(40);

        Plan standard = SubscriptionService.build(1, "A", START, LocalDate.of(2026, 10, 10), 1, LocalDate.of(2026, 9, 1));
        assertThat(standard.stage()).isEqualTo(Stage.STANDARD);
        assertThat(standard.total()).isEqualTo(100);          // the owner alone costs nothing extra
        assertThat(standard.nextChange()).isNull();
    }

    @Test
    void behindMeansDueAndTheNextMonthIsPricedFromToday() {
        // Paid through 10 March, now 15 August: behind, and August is already at the standard price
        Plan p = SubscriptionService.build(1, "A", START, LocalDate.of(2026, 3, 10), 2, LocalDate.of(2026, 8, 15));
        assertThat(p.status()).isEqualTo(Status.DUE);
        assertThat(p.daysLeft()).isNegative();
        assertThat(p.nextAmount()).isEqualTo(100 + 20);
    }

    @Test
    void aClinicWithNoStaffRowsIsNotChargedNegative() {
        Plan p = SubscriptionService.build(1, "A", START, null, 0, LocalDate.of(2026, 3, 1));
        assertThat(p.billableStaff()).isZero();
        assertThat(p.total()).isEqualTo(50);
    }
}
