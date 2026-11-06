package com.clinicemr.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.records.storage.ObjectStorage;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.AdminContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Permanent delete: erases rows (and stored files) for good. Only for things the super admin already removed, so
 * the usual path is Remove first (hidden, recoverable from the database) and Permanently delete second. Stored files
 * are deleted only after the database change commits. Only the audit log keeps a note that it happened.
 */
@Service
public class PlatformPurgeService {

    private static final Logger log = LoggerFactory.getLogger(PlatformPurgeService.class);

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final AuditService audit;

    public PlatformPurgeService(JdbcClient jdbc, ObjectStorage storage, AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
    }

    public record PurgeResult(int visits, int clinics, int patients, int files) {
    }

    /** Running totals while purging, and the stored files to delete once the change commits. */
    private static final class Tally {
        int visits;
        int clinics;
        int patients;
        final List<String> files = new ArrayList<>();

        PurgeResult result() {
            return new PurgeResult(visits, clinics, patients, files.size());
        }
    }

    @Transactional
    public PurgeResult purgeVisit(AdminContext admin, UUID visitId) {
        record Row(long clinicId, boolean removed) {
        }
        Row row = jdbc.sql("SELECT clinic_id, deleted_at IS NOT NULL AS removed FROM encounter WHERE id = :id")
                .param("id", visitId)
                .query(Row.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Visit"));
        if (!row.removed()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REMOVED", "Remove the visit first, then delete it permanently.");
        }
        Tally t = new Tally();
        deleteEncounters(List.of(visitId), t);
        finish(t);
        audit.record("ADMIN", String.valueOf(admin.adminId()), row.clinicId(), "VISIT_PURGED", "encounter", visitId.toString(),
                Map.of("files", t.files.size()));
        return t.result();
    }

    /**
     * Erases a removed doctor: their removed clinics (with those clinics' patients, visits and files), their removed
     * visits anywhere, and the account. Refused while they still have records at a clinic in use.
     */
    @Transactional
    public PurgeResult purgeDoctor(AdminContext admin, long staffId) {
        Boolean removed = jdbc.sql("SELECT deleted_at IS NOT NULL FROM staff_user WHERE id = :id")
                .param("id", staffId)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Doctor"));
        if (!removed) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REMOVED", "Remove the doctor first, then delete them permanently.");
        }
        Tally t = new Tally();
        List<Long> clinics = jdbc.sql("""
                SELECT DISTINCT c.id FROM clinic_member m JOIN clinic c ON c.id = m.clinic_id
                 WHERE m.staff_id = :id AND c.deleted_at IS NOT NULL
                """).param("id", staffId).query(Long.class).list();
        for (long clinicId : clinics) {
            purgeClinic(clinicId, t);
        }

        int live = jdbc.sql("""
                SELECT count(*) FROM encounter WHERE (doctor_id = :id OR created_by = :id) AND deleted_at IS NULL
                """).param("id", staffId).query(Integer.class).single();
        if (live > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STILL_HAS_VISITS",
                    "This doctor still has " + live + " visits at clinics in use. Remove those visits first.");
        }
        deleteEncounters(jdbc.sql("SELECT id FROM encounter WHERE doctor_id = :id OR created_by = :id")
                .param("id", staffId).query(UUID.class).list(), t);

        jdbc.sql("DELETE FROM doctor_template WHERE doctor_id = :id").param("id", staffId).update();
        jdbc.sql("DELETE FROM doctor_favourite WHERE doctor_id = :id").param("id", staffId).update();
        // "Only this doctor" sharing ends with them; it must not widen to the whole clinic
        jdbc.sql("DELETE FROM record_access_grant WHERE doctor_id = :id").param("id", staffId).update();
        jdbc.sql("""
                UPDATE record_access_grant SET access_request_id = NULL
                 WHERE access_request_id IN (SELECT id FROM access_request WHERE staff_id = :id)
                """).param("id", staffId).update();
        jdbc.sql("DELETE FROM access_request WHERE staff_id = :id").param("id", staffId).update();
        jdbc.sql("UPDATE otp_challenge SET issued_by_staff = NULL WHERE issued_by_staff = :id").param("id", staffId).update();
        jdbc.sql("UPDATE payment_request SET decided_by = NULL WHERE decided_by = :id").param("id", staffId).update();
        jdbc.sql("DELETE FROM clinic_member WHERE staff_id = :id").param("id", staffId).update();
        try {
            jdbc.sql("DELETE FROM doctor WHERE staff_id = :id").param("id", staffId).update();
            jdbc.sql("DELETE FROM staff_user WHERE id = :id").param("id", staffId).update();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "STILL_REFERENCED",
                    "This account recorded things (payments, allergies…) at clinics still in use, so it can't be erased. It stays removed.");
        }
        finish(t);
        audit.record("ADMIN", String.valueOf(admin.adminId()), null, "DOCTOR_PURGED", "staff_user", String.valueOf(staffId),
                Map.of("visits", t.visits, "clinics", t.clinics, "patients", t.patients, "files", t.files.size()));
        return t.result();
    }

    /** A removed clinic and everything in it; patients registered only there go too. */
    private void purgeClinic(long clinicId, Tally t) {
        List<Long> profiles = jdbc.sql("SELECT profile_id FROM patient_identifier WHERE clinic_id = :c")
                .param("c", clinicId).query(Long.class).list();
        deleteEncounters(jdbc.sql("SELECT id FROM encounter WHERE clinic_id = :c").param("c", clinicId).query(UUID.class).list(), t);
        deleteDocuments("d.clinic_id = :v", clinicId, t);
        t.files.addAll(jdbc.sql("SELECT proof_key FROM payment_request WHERE clinic_id = :c AND proof_key IS NOT NULL").param("c", clinicId).query(String.class).list());
        for (String table : List.of("payment_request", "payment_entry", "visit_payment", "allergy", "allergy_review",
                "problem_status", "medication_stop", "follow_up")) {
            jdbc.sql("DELETE FROM " + table + " WHERE clinic_id = :c").param("c", clinicId).update();
        }
        List<Long> consents = jdbc.sql("""
                SELECT DISTINCT consent_id FROM record_access_grant
                 WHERE clinic_id = :c OR access_request_id IN (SELECT id FROM access_request WHERE clinic_id = :c)
                """).param("c", clinicId).query(Long.class).list();
        jdbc.sql("""
                DELETE FROM record_access_grant
                 WHERE clinic_id = :c OR access_request_id IN (SELECT id FROM access_request WHERE clinic_id = :c)
                """).param("c", clinicId).update();
        deleteUnusedConsents(consents);
        jdbc.sql("DELETE FROM access_request WHERE clinic_id = :c").param("c", clinicId).update();
        jdbc.sql("DELETE FROM access_log WHERE clinic_id = :c").param("c", clinicId).update();
        jdbc.sql("UPDATE otp_challenge SET clinic_id = NULL WHERE clinic_id = :c").param("c", clinicId).update();
        jdbc.sql("DELETE FROM patient_identifier WHERE clinic_id = :c").param("c", clinicId).update();
        jdbc.sql("DELETE FROM clinic_member WHERE clinic_id = :c").param("c", clinicId).update();
        jdbc.sql("DELETE FROM clinic WHERE id = :c").param("c", clinicId).update();
        t.clinics++;
        for (long profileId : profiles) {
            purgePatientIfOrphaned(profileId, t);
        }
    }

    /** A removed patient no clinic holds any more, with everything recorded about them. */
    private void purgePatientIfOrphaned(long profileId, Tally t) {
        boolean orphaned = jdbc.sql("""
                SELECT p.deleted_at IS NOT NULL
                       AND NOT EXISTS (SELECT 1 FROM patient_identifier pi WHERE pi.profile_id = p.id)
                       AND NOT EXISTS (SELECT 1 FROM encounter e WHERE e.profile_id = p.id)
                  FROM patient_profile p WHERE p.id = :p
                """).param("p", profileId).query(Boolean.class).optional().orElse(false);
        if (!orphaned) {
            return;
        }
        deleteDocuments("d.profile_id = :v", profileId, t);
        t.files.addAll(jdbc.sql("SELECT proof_key FROM payment_request WHERE profile_id = :p AND proof_key IS NOT NULL").param("p", profileId).query(String.class).list());
        List<Long> consents = jdbc.sql("SELECT id FROM consent WHERE profile_id = :p").param("p", profileId).query(Long.class).list();
        for (String table : List.of("payment_request", "payment_entry", "visit_payment", "allergy", "allergy_review",
                "problem_status", "medication_stop", "vital_sign", "diagnosis", "investigation_order", "follow_up",
                "record_access_grant", "access_log", "profile_access")) {
            jdbc.sql("DELETE FROM " + table + " WHERE profile_id = :p").param("p", profileId).update();
        }
        deleteUnusedConsents(consents);
        jdbc.sql("UPDATE patient_profile SET merged_into_id = NULL WHERE merged_into_id = :p").param("p", profileId).update();
        jdbc.sql("DELETE FROM patient_profile WHERE id = :p").param("p", profileId).update();
        t.patients++;
    }

    /** Visits and everything recorded in them: notes, vitals, diagnoses, medicines, tests, payments, attached files. */
    private void deleteEncounters(List<UUID> ids, Tally t) {
        if (ids.isEmpty()) {
            return;
        }
        // An allergy noted during a visit belongs to the patient; it stays, without the link
        jdbc.sql("UPDATE allergy SET encounter_id = NULL WHERE encounter_id IN (:ids)").param("ids", ids).update();
        for (UUID id : ids) {
            deleteDocuments("d.encounter_id = :v", id, t);
        }
        jdbc.sql("DELETE FROM prescription_item WHERE prescription_id IN (SELECT id FROM prescription WHERE encounter_id IN (:ids))")
                .param("ids", ids).update();
        for (String table : List.of("payment_entry", "visit_payment", "prescription", "vital_sign", "diagnosis",
                "investigation_order", "follow_up", "encounter_note", "encounter_version")) {
            jdbc.sql("DELETE FROM " + table + " WHERE encounter_id IN (:ids)").param("ids", ids).update();
        }
        t.visits += jdbc.sql("DELETE FROM encounter WHERE id IN (:ids)").param("ids", ids).update();
    }

    private void deleteDocuments(String where, Object value, Tally t) {
        String docs = "SELECT d.id FROM medical_document d WHERE " + where;
        record Keys(String storageKey, String thumbKey) {
        }
        for (Keys k : jdbc.sql("SELECT storage_key, thumb_key FROM document_file WHERE document_id IN (" + docs + ")")
                .param("v", value).query(Keys.class).list()) {
            t.files.add(k.storageKey());
            if (k.thumbKey() != null) {
                t.files.add(k.thumbKey());
            }
        }
        jdbc.sql("DELETE FROM document_file WHERE document_id IN (" + docs + ")").param("v", value).update();
        jdbc.sql("DELETE FROM medical_document d WHERE " + where).param("v", value).update();
    }

    private void deleteUnusedConsents(List<Long> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("""
                    DELETE FROM consent c WHERE c.id IN (:ids)
                       AND NOT EXISTS (SELECT 1 FROM record_access_grant g WHERE g.consent_id = c.id)
                    """).param("ids", ids).update();
        }
    }

    /** Stored files go once the database change is final; a file that can't be deleted is only logged. */
    private void finish(Tally t) {
        List<String> files = List.copyOf(t.files);
        if (files.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : files) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("Could not delete stored file {}: {}", key, e.getMessage());
                    }
                }
            }
        });
    }
}
