package com.clinicemr.shared.security;

/** A patient signed in to the app with their phone number. The account speaks for every profile it manages. */
public record PatientContext(long accountId, String phoneE164) {
}
