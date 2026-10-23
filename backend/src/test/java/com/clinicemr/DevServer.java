package com.clinicemr;

import java.nio.file.Files;
import java.nio.file.Path;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

/**
 * Runs the backend locally against an embedded PostgreSQL 16 kept in {@code backend/target/dev-db}, with documents
 * stored on disk in {@code backend/target/dev-storage}, so the app can be tried without Docker or a cloud account.
 * Data survives restarts until {@code mvn clean}. Set STORAGE_TYPE=s3 (and STORAGE_*) to use MinIO or Oracle instead.
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
        // Documents on disk under target/dev-storage, unless STORAGE_TYPE points at MinIO or a cloud bucket
        if (System.getenv("STORAGE_TYPE") == null) {
            System.setProperty("app.storage.type", "local");
        }
        SpringApplication.run(ClinicEmrApplication.class, args);
    }
}
