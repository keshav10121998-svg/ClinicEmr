package com.clinicemr.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.clinicemr.shared.PhoneNumbers;
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

@RestController
@RequestMapping("/api/v1/auth/staff")
public class StaffAuthController {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    // Checked when the email is unknown, so response time doesn't reveal which emails exist
    private final String dummyHash;

    public StaffAuthController(JdbcClient jdbc, PasswordEncoder passwords, TokenService tokens) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    /**
     * {@code username} is the email address or the 10-digit mobile number set for the account. Older clients send
     * {@code email} instead.
     */
    public record LoginRequest(String username, String email, @NotBlank String password) {

        String identifier() {
            String id = username != null && !username.isBlank() ? username : email;
            return id == null ? "" : id.trim();
        }
    }

    public record ClinicMembership(long clinicId, String clinicName, String clinicStatus, List<String> roles) {
    }

    public record LoginResponse(String accessToken, Instant expiresAt, long staffId, String name,
                                List<ClinicMembership> clinics) {
    }

    private record StaffRow(long id, String email, String name, String passwordHash, String status) {
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        String id = req.identifier();
        Optional<StaffRow> staff;
        if (id.contains("@")) {
            staff = jdbc.sql("SELECT id, email, name, password_hash, status FROM staff_user WHERE lower(email) = lower(:email)")
                    .param("email", id)
                    .query(StaffRow.class)
                    .optional();
        } else if (PhoneNumbers.isValid(id)) {
            staff = jdbc.sql("SELECT id, email, name, password_hash, status FROM staff_user WHERE phone_e164 = :phone")
                    .param("phone", PhoneNumbers.toE164(id))
                    .query(StaffRow.class)
                    .optional();
        } else {
            throw ApiException.badRequest("INVALID_USERNAME", "Enter your email address or 10-digit mobile number.");
        }

        boolean ok = passwords.matches(req.password(), staff.map(StaffRow::passwordHash).orElse(dummyHash))
                && staff.isPresent() && "ACTIVE".equals(staff.get().status());
        if (!ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email/mobile number or password is incorrect.");
        }

        StaffRow s = staff.get();
        TokenService.IssuedToken token = tokens.issueStaffToken(s.id(), s.email(), s.name());
        return new LoginResponse(token.value(), token.expiresAt(), s.id(), s.name(), memberships(s.id()));
    }

    private List<ClinicMembership> memberships(long staffId) {
        record Row(long clinicId, String clinicName, String clinicStatus, String role) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT c.id AS clinic_id, c.name AS clinic_name, c.status AS clinic_status, m.role
                  FROM clinic_member m JOIN clinic c ON c.id = m.clinic_id
                 WHERE m.staff_id = :staffId
                 ORDER BY c.name, m.role
                """)
                .param("staffId", staffId)
                .query(Row.class)
                .list();
        return rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(Row::clinicId, java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .values().stream()
                .map(list -> new ClinicMembership(list.get(0).clinicId(), list.get(0).clinicName(),
                        list.get(0).clinicStatus(), list.stream().map(Row::role).toList()))
                .toList();
    }
}
