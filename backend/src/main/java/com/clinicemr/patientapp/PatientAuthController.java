package com.clinicemr.patientapp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.access.OtpCodes;
import com.clinicemr.notification.Notifier;
import com.clinicemr.shared.PhoneNumbers;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patient sign-in. Three ways in, none needing paid SMS unless one is configured:
 * <ul>
 *   <li>a one-time code shown at the clinic counter (scanned as a QR, or typed with the mobile number);</li>
 *   <li>mobile number and PIN, set after the first code sign-in, for signing in at home;</li>
 *   <li>a code sent by SMS, when an SMS provider is set up.</li>
 * </ul>
 * On sign-in, every profile registered with that number at any clinic is linked to the account as its owner.
 */
@RestController
@RequestMapping("/api/v1/auth/patient")
public class PatientAuthController {

    static final Duration OTP_VALIDITY = Duration.ofMinutes(10);
    static final int MAX_CODES_PER_PHONE_PER_HOUR = 5;
    static final int MAX_PIN_FAILURES = 5;
    static final Duration PIN_LOCK = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final OtpCodes otp;
    private final Notifier notifier;
    private final TokenService tokens;
    private final AuditService audit;
    private final PasswordEncoder passwords;

    public PatientAuthController(JdbcClient jdbc, OtpCodes otp, Notifier notifier, TokenService tokens, AuditService audit,
                                 PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.otp = otp;
        this.notifier = notifier;
        this.tokens = tokens;
        this.audit = audit;
        this.passwords = passwords;
    }

    public record CodeRequest(@NotBlank String phone) {
    }

    public record CodeSent(UUID challengeId, String phone, Instant expiresAt) {
    }

    public record VerifyCode(@NotNull UUID challengeId,
                             @NotNull @Pattern(regexp = "\\d{6}", message = "must be the 6-digit code") String otp) {
    }

    /** A code typed in by hand: the mobile number tells us which code it is. */
    public record VerifyPhoneCode(@NotBlank String phone,
                                  @NotNull @Pattern(regexp = "\\d{6}", message = "must be the 6-digit code") String otp) {
    }

    public record PinSignIn(@NotBlank String phone,
                            @NotNull @Pattern(regexp = "\\d{4,6}", message = "must be 4 to 6 digits") String pin) {
    }

    /** {@code pinSet} false means the app should ask the patient to choose a PIN now. */
    public record SignedIn(String accessToken, Instant expiresAt, long accountId, String phone, int profiles, boolean pinSet) {
    }

    @PostMapping("/otp")
    @Transactional
    public CodeSent requestCode(@Valid @RequestBody CodeRequest req) {
        String phone = PhoneNumbers.toE164(req.phone());
        int recent = jdbc.sql("""
                SELECT count(*) FROM otp_challenge WHERE phone_e164 = :phone AND created_at > now() - interval '1 hour'
                """)
                .param("phone", phone)
                .query(Integer.class)
                .single();
        if (recent >= MAX_CODES_PER_PHONE_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED", "Too many codes requested. Try again in an hour.");
        }
        UUID id = UUID.randomUUID();
        String code = otp.generate();
        Instant expiresAt = Instant.now().plus(OTP_VALIDITY);
        jdbc.sql("""
                INSERT INTO otp_challenge (id, phone_e164, purpose, code_hash, expires_at)
                VALUES (:id, :phone, 'PATIENT_LOGIN', :hash, :expiresAt)
                """)
                .param("id", id)
                .param("phone", phone)
                .param("hash", otp.hash(code, id.toString()))
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();
        notifier.smsAfterCommit(phone, code + " is your ClinicEMR sign-in code. Valid 10 minutes. Don't share it with anyone.");
        return new CodeSent(id, PhoneNumbers.mask(phone), expiresAt);
    }

    /** A code identified by its request id: an SMS code, or a counter code scanned from the QR. */
    @PostMapping("/otp/verify")
    @Transactional(noRollbackFor = ApiException.class)
    public SignedIn verify(@Valid @RequestBody VerifyCode req) {
        return signInWithCode(req.challengeId(), req.otp());
    }

    /** A code typed in with the mobile number: checked against the newest unused code for that number. */
    @PostMapping("/code/verify")
    @Transactional(noRollbackFor = ApiException.class)
    public SignedIn verifyByPhone(@Valid @RequestBody VerifyPhoneCode req) {
        String phone = PhoneNumbers.toE164(req.phone());
        UUID id = jdbc.sql("""
                SELECT id FROM otp_challenge
                 WHERE phone_e164 = :phone AND consumed_at IS NULL AND expires_at > now() AND attempts < :max
                 ORDER BY created_at DESC LIMIT 1
                """)
                .param("phone", phone)
                .param("max", OtpCodes.MAX_ATTEMPTS)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.GONE, "OTP_EXPIRED",
                        "There's no valid code for this number. Ask the clinic for a new one."));
        return signInWithCode(id, req.otp());
    }

    @PostMapping("/pin")
    @Transactional(noRollbackFor = ApiException.class)
    public SignedIn signInWithPin(@Valid @RequestBody PinSignIn req) {
        String phone = PhoneNumbers.toE164(req.phone());
        record Account(long id, String pinHash, int pinFailures, Instant pinLockedUntil, int sessionVersion) {
        }
        // Same answer whether the number is unknown or has no PIN, so the page doesn't reveal who uses the app
        ApiException wrong = new ApiException(HttpStatus.UNAUTHORIZED, "PIN_INVALID",
                "Mobile number or PIN is not right. If you haven't set a PIN yet, ask your clinic for a sign-in code.");
        Account a = jdbc.sql("""
                SELECT id, pin_hash, pin_failures, pin_locked_until, session_version
                  FROM account WHERE phone_e164 = :phone AND deleted_at IS NULL AND status = 'ACTIVE' FOR UPDATE
                """)
                .param("phone", phone)
                .query(Account.class)
                .optional()
                .orElseThrow(() -> wrong);
        if (a.pinLockedUntil() != null && Instant.now().isBefore(a.pinLockedUntil())) {
            throw new ApiException(HttpStatus.LOCKED, "PIN_LOCKED",
                    "Too many wrong PINs. Try again in 15 minutes, or ask your clinic for a sign-in code.",
                    Map.of("lockedUntil", a.pinLockedUntil().toString()));
        }
        if (a.pinHash() == null) {
            throw wrong;
        }
        if (!passwords.matches(req.pin(), a.pinHash())) {
            int failures = a.pinFailures() + 1;
            boolean lock = failures >= MAX_PIN_FAILURES;
            jdbc.sql("""
                    UPDATE account SET pin_failures = :failures, pin_locked_until = :lockedUntil WHERE id = :id
                    """)
                    .param("failures", lock ? 0 : failures)
                    .param("lockedUntil", lock ? java.sql.Timestamp.from(Instant.now().plus(PIN_LOCK)) : null)
                    .param("id", a.id())
                    .update();
            if (lock) {
                audit.record("PATIENT", "account:" + a.id(), null, "PATIENT_PIN_LOCKED", "account", String.valueOf(a.id()), Map.of());
                throw new ApiException(HttpStatus.LOCKED, "PIN_LOCKED",
                        "Too many wrong PINs. Try again in 15 minutes, or ask your clinic for a sign-in code.");
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PIN_INVALID", wrong.getMessage(),
                    Map.of("attemptsLeft", MAX_PIN_FAILURES - failures));
        }
        jdbc.sql("UPDATE account SET pin_failures = 0, pin_locked_until = NULL, last_login_at = now() WHERE id = :id")
                .param("id", a.id())
                .update();
        return finishSignIn(a.id(), phone, "pin");
    }

    private SignedIn signInWithCode(UUID challengeId, String code) {
        record Challenge(String phoneE164, String codeHash, int attempts, Instant expiresAt, Instant consumedAt) {
        }
        Challenge c = jdbc.sql("""
                SELECT phone_e164, code_hash, attempts, expires_at, consumed_at FROM otp_challenge WHERE id = :id FOR UPDATE
                """)
                .param("id", challengeId)
                .query(Challenge.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Sign-in code"));
        if (c.consumedAt() != null || Instant.now().isAfter(c.expiresAt()) || c.attempts() >= OtpCodes.MAX_ATTEMPTS) {
            throw new ApiException(HttpStatus.GONE, "OTP_EXPIRED", "This code can't be used any more. Ask for a new one.");
        }
        if (!otp.matches(code, challengeId.toString(), c.codeHash())) {
            jdbc.sql("UPDATE otp_challenge SET attempts = attempts + 1 WHERE id = :id").param("id", challengeId).update();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "OTP_INVALID", "That code is not right.",
                    Map.of("attemptsLeft", Math.max(0, OtpCodes.MAX_ATTEMPTS - c.attempts() - 1)));
        }
        jdbc.sql("UPDATE otp_challenge SET consumed_at = now() WHERE id = :id").param("id", challengeId).update();

        long accountId = jdbc.sql("""
                INSERT INTO account (phone_e164, last_login_at) VALUES (:phone, now())
                ON CONFLICT (phone_e164) DO UPDATE SET last_login_at = now(), pin_failures = 0, pin_locked_until = NULL
                RETURNING id
                """)
                .param("phone", c.phoneE164())
                .query(Long.class)
                .single();
        return finishSignIn(accountId, c.phoneE164(), "code");
    }

    private SignedIn finishSignIn(long accountId, String phone, String method) {
        // Profiles registered at clinics with this number become the account's own
        jdbc.sql("""
                INSERT INTO profile_access (account_id, profile_id, role)
                SELECT :accountId, p.id, 'OWNER' FROM patient_profile p
                 WHERE p.primary_phone_e164 = :phone AND p.deleted_at IS NULL AND p.merged_into_id IS NULL
                ON CONFLICT DO NOTHING
                """)
                .param("accountId", accountId)
                .param("phone", phone)
                .update();
        jdbc.sql("""
                UPDATE patient_profile SET claimed = true
                 WHERE id IN (SELECT profile_id FROM profile_access WHERE account_id = :accountId) AND NOT claimed
                """)
                .param("accountId", accountId)
                .update();
        record State(int profiles, boolean pinSet, int sessionVersion) {
        }
        State s = jdbc.sql("""
                SELECT (SELECT count(*) FROM profile_access WHERE account_id = a.id)::int AS profiles,
                       a.pin_hash IS NOT NULL AS pin_set, a.session_version
                  FROM account a WHERE a.id = :accountId
                """)
                .param("accountId", accountId)
                .query(State.class)
                .single();
        audit.record("PATIENT", "account:" + accountId, null, "PATIENT_SIGNED_IN", "account", String.valueOf(accountId),
                Map.of("method", method));
        TokenService.IssuedToken token = tokens.issuePatientToken(accountId, phone, method, s.sessionVersion());
        return new SignedIn(token.value(), token.expiresAt(), accountId, PhoneNumbers.mask(phone), s.profiles(), s.pinSet());
    }
}
