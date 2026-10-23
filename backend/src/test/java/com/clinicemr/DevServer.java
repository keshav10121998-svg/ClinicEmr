package com.clinicemr;

import java.nio.file.Files;
import java.nio.file.Path;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

/**
 * Runs the backend locally against an embedded PostgreSQL 16 kept in {@code backend/target/dev-db}, so the app can
 * be tried without Docker. Data survives restarts until {@code mvn clean}. Document uploads need an S3-compatible
 * store (MinIO or Oracle) and won't work without one.
 * <p>
 * Start with: {@code ./mvnw spring-boot:test-run}
 */
public class DevServer {

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("target", "dev-db");
        Files.createDirectories(dataDir);
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .setPort(54329)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (Exception ignored) {
                // exiting anyway
            }
        }));
        System.setProperty("spring.datasource.url", pg.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        SpringApplication.run(ClinicEmrApplication.class, args);
    }
}
