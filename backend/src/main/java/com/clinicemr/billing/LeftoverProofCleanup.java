package com.clinicemr.billing;

import java.util.List;

import com.clinicemr.records.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Payment proofs are deleted when the doctor approves or rejects the request. This removes the ones left from before
 * that rule (or whose deletion failed) each time the server starts: every proof of a request that is no longer
 * pending. A file that still can't be deleted stays recorded and is tried again at the next start.
 */
@Component
public class LeftoverProofCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LeftoverProofCleanup.class);

    private final JdbcClient jdbc;
    private final ObjectStorage storage;

    public LeftoverProofCleanup(JdbcClient jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        record Left(String id, String key) {
        }
        List<Left> left = jdbc.sql("""
                SELECT id::text AS id, proof_key AS key FROM payment_request
                 WHERE status <> 'PENDING' AND proof_key IS NOT NULL
                """).query(Left.class).list();
        int removed = 0;
        for (Left l : left) {
            try {
                storage.delete(l.key());
                jdbc.sql("UPDATE payment_request SET proof_key = NULL, proof_type = NULL, proof_deleted_at = now() WHERE id = CAST(:id AS uuid)")
                        .param("id", l.id()).update();
                removed++;
            } catch (RuntimeException e) {
                log.warn("Could not delete old payment proof {}: {}", l.key(), e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("Deleted {} payment proof file(s) of requests that were already decided.", removed);
        }
    }
}
