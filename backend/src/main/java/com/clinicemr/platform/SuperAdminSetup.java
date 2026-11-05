package com.clinicemr.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public SuperAdminSetup(JdbcClient jdbc, PasswordEncoder passwords,
                           @Value("${app.super-admin.email:${SUPER_ADMIN_EMAIL:}}") String email,
                           @Value("${app.super-admin.password:${SUPER_ADMIN_PASSWORD:}}") String password,
                           @Value("${app.super-admin.name:${SUPER_ADMIN_NAME:Super admin}}") String name) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.email = email.trim();
        this.password = password;
        this.name = name.isBlank() ? "Super admin" : name.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_admin)").query(Boolean.class).single();
        if (exists) {
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
        jdbc.sql("INSERT INTO platform_admin (email, name, password_hash) VALUES (:email, :name, :hash) ON CONFLICT DO NOTHING")
                .param("email", email.toLowerCase())
                .param("name", name)
                .param("hash", passwords.encode(password))
                .update();
        log.info("Super admin created for {}. Sign in at /admin.", email.toLowerCase());
    }
}
