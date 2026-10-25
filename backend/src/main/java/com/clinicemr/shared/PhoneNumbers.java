package com.clinicemr.shared;

import com.clinicemr.shared.error.ApiException;

/**
 * Indian mobile numbers only: exactly 10 digits starting with 6, 7, 8 or 9, stored as E.164 ({@code +91…}).
 * Spaces, dashes and a leading {@code +91}, {@code 91} or {@code 0} are allowed in what people type or paste.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    private static final String MESSAGE = "Enter a 10-digit mobile number.";

    public static String toE164(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("INVALID_PHONE", "Mobile number is required.");
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 10 || digits.charAt(0) < '6') {
            throw ApiException.badRequest("INVALID_PHONE", MESSAGE);
        }
        return "+91" + digits;
    }

    /** True if {@code raw} is a mobile number in a form {@link #toE164} accepts. */
    public static boolean isValid(String raw) {
        try {
            toE164(raw);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /** Masks all but the last four digits, e.g. {@code ******3210}. */
    public static String mask(String e164) {
        String digits = e164.replaceAll("\\D", "");
        String last4 = digits.substring(Math.max(0, digits.length() - 4));
        return "******" + last4;
    }
}
