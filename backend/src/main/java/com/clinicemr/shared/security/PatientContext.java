package com.clinicemr.shared.security;

import java.time.Instant;

/**
 * A patient signed in to the app with their phone number. The account speaks for every profile it manages.
 * {@code signInMethod} ("code" or "pin") and {@code signedInAt} say how and when this sign-in was made.
 */
public record PatientContext(long accountId, String phoneE164, String signInMethod, Instant signedInAt) {

    /** Signed in with a one-time code in the last 15 minutes: allowed to set a new PIN without the old one. */
    public boolean recentCodeSignIn() {
        return "code".equals(signInMethod) && signedInAt != null
                && signedInAt.isAfter(Instant.now().minus(java.time.Duration.ofMinutes(15)));
    }
}
