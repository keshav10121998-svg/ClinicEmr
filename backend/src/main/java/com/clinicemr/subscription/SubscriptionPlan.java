package com.clinicemr.subscription;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The price list, in whole rupees a month: the first month is free, the next six months cost the launch price, and
 * every month after that the standard price. From the second month each staff member except the first (the clinic's
 * owner) adds a small amount. A "month" runs from the clinic's start date to the same day next month.
 */
public final class SubscriptionPlan {

    public static final int LAUNCH_PRICE = 50;
    public static final int STANDARD_PRICE = 100;
    public static final int STAFF_PRICE = 20;
    public static final int LAUNCH_MONTHS = 6;

    public enum Stage { FREE_MONTH, LAUNCH, STANDARD }

    private SubscriptionPlan() {
    }

    /** 1 for the first (free) month, 2 for the next, and so on. */
    public static int monthNumber(LocalDate start, LocalDate on) {
        return on.isBefore(start) ? 1 : (int) ChronoUnit.MONTHS.between(start, on) + 1;
    }

    public static Stage stage(int monthNumber) {
        return monthNumber == 1 ? Stage.FREE_MONTH : monthNumber <= 1 + LAUNCH_MONTHS ? Stage.LAUNCH : Stage.STANDARD;
    }

    public static int basePrice(Stage stage) {
        return switch (stage) {
            case FREE_MONTH -> 0;
            case LAUNCH -> LAUNCH_PRICE;
            case STANDARD -> STANDARD_PRICE;
        };
    }

    /** {@code billableStaff}: staff beyond the owner. Nothing is charged for them in the free month. */
    public static int staffCharge(Stage stage, int billableStaff) {
        return stage == Stage.FREE_MONTH ? 0 : Math.max(0, billableStaff) * STAFF_PRICE;
    }

    /** The first day after the free month. */
    public static LocalDate freeUntil(LocalDate start) {
        return start.plusMonths(1);
    }

    /** The first day at the standard price. */
    public static LocalDate standardFrom(LocalDate start) {
        return start.plusMonths(1 + LAUNCH_MONTHS);
    }
}
