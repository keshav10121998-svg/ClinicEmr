package com.clinicemr.shared;

import com.clinicemr.shared.error.ApiException;

/**
 * Normalises phone numbers to E.164. Ten-digit Indian mobile numbers get the +91 prefix.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    public static String toE164(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("INVALID_PHONE", "Phone number is required.");
        }
        String trimmed = raw.trim();
        boolean hasPlus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");

        if (!hasPlus) {
            if (digits.length() == 11 && digits.startsWith("0")) {
                digits = digits.substring(1);
            }
            if (digits.length() == 10 && digits.charAt(0) >= '6' && digits.charAt(0) <= '9') {
                return "+91" + digits;
            }
            if (digits.length() == 12 && digits.startsWith("91")) {
                return "+" + digits;
            }
            throw ApiException.badRequest("INVALID_PHONE", "Enter a 10-digit mobile number or include the country code.");
        }
        if (digits.length() < 8 || digits.length() > 15) {
            throw ApiException.badRequest("INVALID_PHONE", "Phone number has the wrong number of digits.");
        }
        if (digits.startsWith("91") && digits.length() != 12) {
            throw ApiException.badRequest("INVALID_PHONE", "Indian numbers need 10 digits after +91.");
        }
        return "+" + digits;
    }

    /** Masks all but the last four digits, e.g. {@code ******3210}. */
    public static String mask(String e164) {
        String digits = e164.replaceAll("\\D", "");
        String last4 = digits.substring(Math.max(0, digits.length() - 4));
        return "******" + last4;
    }
}
