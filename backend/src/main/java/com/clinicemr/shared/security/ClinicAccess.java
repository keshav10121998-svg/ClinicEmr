package com.clinicemr.shared.security;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.clinicemr.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Whether a clinic can currently change anything. The super admin switches a clinic on until a date (inclusive);
 * after that day, or before it was ever switched on, the clinic can still read its records but every change is refused.
 */
public final class ClinicAccess {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String ENDED = "SUBSCRIPTION_ENDED";

    private ClinicAccess() {
    }

    public static LocalDate today() {
        return LocalDate.now(IST);
    }

    /** True while {@code until} is today or later. */
    public static boolean active(LocalDate until, LocalDate today) {
        return until != null && !today.isAfter(until);
    }

    /** The error a blocked clinic gets for a change. The status is 402 (Payment Required). */
    public static ApiException ended(LocalDate until) {
        String message = until == null
                ? "This clinic isn't switched on yet, so you can only view records. Please contact ClinicEMR to start."
                : "This clinic's plan ended on " + until.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                + ", so you can only view records until it is renewed. Please contact ClinicEMR to renew.";
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, ENDED, message);
    }
}
