package com.clinicemr.patientapp;

import java.util.Map;
import java.util.Set;

import com.clinicemr.patientapp.PatientAuthController.SignedIn;
import com.clinicemr.shared.PhoneNumbers;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.PatientContext;
import com.clinicemr.shared.security.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in patient's own sign-in settings: their PIN, and keeping the phone signed in. */
@RestController
@RequestMapping("/api/v1/patient")
public class PatientAccountController {

    // PINs anyone would try first
    private static final Set<String> WEAK = Set.of("1234", "12345", "123456", "4321", "54321", "654321", "1212", "121212",
            "1122", "112233", "2580", "0852", "1010", "2000", "2020", "1947");

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuditService audit;

    public PatientAccountController(JdbcClient jdbc, PasswordEncoder passwords, TokenService tokens, AuditService audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
    }

    public record AccountView(String phone, boolean pinSet) {
    }

    /** {@code currentPin} is needed to change an existing PIN, unless they just signed in with a one-time code. */
    public record SetPin(@NotNull @Pattern(regexp = "\\d{4,6}", message = "must be 4 to 6 digits") String pin,
                         String currentPin) {
    }

    @GetMapping("/account")
    public AccountView account(PatientContext p) {
        boolean pinSet = jdbc.sql("SELECT pin_hash IS NOT NULL FROM account WHERE id = :id")
                .param("id", p.accountId())
                .query(Boolean.class)
                .single();
        return new AccountView(PhoneNumbers.mask(p.phoneE164()), pinSet);
    }

    /**
     * Sets or changes the PIN. Every other phone signed in to this account is signed out; this one gets a new
     * sign-in in the response.
     */
    @PostMapping("/pin")
    @Transactional
    public SignedIn setPin(PatientContext p, @Valid @RequestBody SetPin req) {
        String existing = jdbc.sql("SELECT pin_hash FROM account WHERE id = :id FOR UPDATE")
                .param("id", p.accountId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (existing != null && !p.recentCodeSignIn()
                && (req.currentPin() == null || !passwords.matches(req.currentPin(), existing))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_PIN_REQUIRED",
                    "Enter your current PIN. Forgot it? Ask your clinic for a sign-in code, then set a new PIN.");
        }
        if (isWeak(req.pin())) {
            throw ApiException.badRequest("PIN_TOO_EASY", "That PIN is too easy to guess. Avoid repeated or sequential digits.");
        }
        int version = jdbc.sql("""
                UPDATE account SET pin_hash = :hash, pin_set_at = now(), pin_failures = 0, pin_locked_until = NULL,
                                   session_version = session_version + 1
                 WHERE id = :id RETURNING session_version
                """)
                .param("hash", passwords.encode(req.pin()))
                .param("id", p.accountId())
                .query(Integer.class)
                .single();
        audit.record("PATIENT", "account:" + p.accountId(), null, existing == null ? "PATIENT_PIN_SET" : "PATIENT_PIN_CHANGED",
                "account", String.valueOf(p.accountId()), Map.of());
        return issue(p, "pin", version);
    }

    /** Called when the app opens: swaps the sign-in for a fresh 90-day one, so active patients stay signed in. */
    @PostMapping("/session/renew")
    public SignedIn renew(PatientContext p) {
        int version = jdbc.sql("SELECT session_version FROM account WHERE id = :id")
                .param("id", p.accountId())
                .query(Integer.class)
                .single();
        return issue(p, "renewed", version);
    }

    private SignedIn issue(PatientContext p, String method, int version) {
        record State(int profiles, boolean pinSet) {
        }
        State s = jdbc.sql("""
                SELECT (SELECT count(*) FROM profile_access WHERE account_id = a.id)::int AS profiles,
                       a.pin_hash IS NOT NULL AS pin_set
                  FROM account a WHERE a.id = :id
                """)
                .param("id", p.accountId())
                .query(State.class)
                .single();
        TokenService.IssuedToken token = tokens.issuePatientToken(p.accountId(), p.phoneE164(), method, version);
        return new SignedIn(token.value(), token.expiresAt(), p.accountId(), PhoneNumbers.mask(p.phoneE164()), s.profiles(), s.pinSet());
    }

    static boolean isWeak(String pin) {
        if (WEAK.contains(pin) || pin.chars().distinct().count() == 1) {
            return true;
        }
        boolean up = true;
        boolean down = true;
        for (int i = 1; i < pin.length(); i++) {
            int d = pin.charAt(i) - pin.charAt(i - 1);
            up &= d == 1;
            down &= d == -1;
        }
        return up || down;
    }
}
