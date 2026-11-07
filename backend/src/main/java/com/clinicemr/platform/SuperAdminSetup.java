package com.clinicemr.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the super admin the first time the server starts, from SUPER_ADMIN_EMAIL and SUPER_ADMIN_PASSWORD (and
 * optionally SUPER_ADMIN_NAME). Once a super admin exists this does nothing, so the settings can stay in place; the
 * password is never stored in the code. Without the settings the server starts normally and logs how to add one.
 * <p>
 * Forgotten or wrong password: set SUPER_ADMIN_RESET=true together with SUPER_ADMIN_EMAIL and SUPER_ADMIN_PASSWORD and
 * restart. That email is then given exactly that password (created if it isn't there yet, and any sign-in lock is
 * cleared). Remove SUPER_ADMIN_RESET afterwards, or the password is set again at every start.
 */
@Component
public class SuperAdminSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminSetup.class);
    static final int MIN_PASSWORD = 12;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final String email;
    private final String password;
    private final String name;
    private final boolean reset;

    @Autowired
    public SuperAdminSetup(JdbcClient jdbc, PasswordEncoder passwords,
                           @Value("${app.super-admin.email:${SUPER_ADMIN_EMAIL:}}") String email,
                           @Value("${app.super-admin.password:${SUPER_ADMIN_PASSWORD:}}") String password,
                           @Value("${app.super-admin.name:${SUPER_ADMIN_NAME:Super admin}}") String name,
                           @Value("${app.super-admin.reset:${SUPER_ADMIN_RESET:false}}") boolean reset) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.email = email.trim();
        // A space or new line pasted after the password in a settings box is not part of the password
        this.password = password.trim();
        this.name = name.isBlank() ? "Super admin" : name.trim();
        this.reset = reset;
    }

    public SuperAdminSetup(JdbcClient jdbc, PasswordEncoder passwords, String email, String password, String name) {
        this(jdbc, passwords, email, password, name, false);
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_admin)").query(Boolean.class).single();
        if (exists && !reset) {
            return;
        }
        if (email.isEmpty() || password.isEmpty()) {
            log.warn("No super admin yet. Set SUPER_ADMIN_EMAIL and SUPER_ADMIN_PASSWORD and restart to create one.");
            return;
        }
        if (!email.contains("@") || password.length() < MIN_PASSWORD) {
            log.error("Super admin not created: SUPER_ADMIN_EMAIL must be an email address and SUPER_ADMIN_PASSWORD at least {} characters.",
                    MIN_PASSWORD);
            return;
        }
        if (reset) {
            // Sets this email's password to exactly what is in the settings, and unlocks it
            jdbc.sql("""
                    INSERT INTO platform_admin (email, name, password_hash) VALUES (:email, :name, :hash)
                    ON CONFLICT (email) DO UPDATE SET password_hash = :hash, failed_attempts = 0, locked_until = NULL
                    """)
                    .param("email", email.toLowerCase())
                    .param("name", name)
                    .param("hash", passwords.encode(password))
                    .update();
            log.warn("Super admin password for {} was set from SUPER_ADMIN_PASSWORD (SUPER_ADMIN_RESET is on). Sign in at /admin, "
                    + "then remove SUPER_ADMIN_RESET so it is not set again at every start.", email.toLowerCase());
            return;
        }
        jdbc.sql("INSERT INTO platform_admin (email, name, password_hash) VALUES (:email, :name, :hash) ON CONFLICT DO NOTHING")
                .param("email", email.toLowerCase())
                .param("name", name)
                .param("hash", passwords.encode(password))
                .update();
        log.info("Super admin created for {}. Sign in at /admin.", email.toLowerCase());
    }
}
