package com.clinicemr.platform;

import java.time.Instant;
import java.util.Optional;

import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Super admin sign-in. Five wrong passwords lock it for 15 minutes. */
@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthController {

    private static final int MAX_FAILURES = 5;
    private static final java.time.Duration LOCK = java.time.Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuditService audit;
    // Checked when the email is unknown, so response time doesn't reveal it
    private final String dummyHash;

    public AdminAuthController(JdbcClient jdbc, PasswordEncoder passwords, TokenService tokens, AuditService audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, Instant expiresAt, String name, String email) {
    }

    private record AdminRow(long id, String email, String name, String passwordHash, int failedAttempts, Instant lockedUntil) {
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        Optional<AdminRow> admin = jdbc.sql("""
                SELECT id, email, name, password_hash, failed_attempts, locked_until
                  FROM platform_admin WHERE email = lower(:email)
                """)
                .param("email", req.email().trim())
                .query(AdminRow.class)
                .optional();
        if (admin.isPresent() && admin.get().lockedUntil() != null && admin.get().lockedUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOCKED", "Too many wrong passwords. Try again in 15 minutes.");
        }
        boolean ok = passwords.matches(req.password(), admin.map(AdminRow::passwordHash).orElse(dummyHash)) && admin.isPresent();
        if (!ok) {
            admin.ifPresent(a -> jdbc.sql("""
                    UPDATE platform_admin
                       SET failed_attempts = CASE WHEN failed_attempts + 1 >= :max THEN 0 ELSE failed_attempts + 1 END,
                           locked_until = CASE WHEN failed_attempts + 1 >= :max THEN now() + CAST(:lock AS interval) ELSE NULL END
                     WHERE id = :id
                    """)
                    .param("max", MAX_FAILURES)
                    .param("lock", LOCK.toMinutes() + " minutes")
                    .param("id", a.id())
                    .update());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
        }
        AdminRow a = admin.get();
        jdbc.sql("UPDATE platform_admin SET failed_attempts = 0, locked_until = NULL, last_login_at = now() WHERE id = :id")
                .param("id", a.id())
                .update();
        audit.record("ADMIN", String.valueOf(a.id()), null, "ADMIN_SIGNED_IN", "platform_admin", String.valueOf(a.id()), java.util.Map.of());
        TokenService.IssuedToken token = tokens.issueAdminToken(a.id(), a.email());
        return new LoginResponse(token.value(), token.expiresAt(), a.name(), a.email());
    }
}
