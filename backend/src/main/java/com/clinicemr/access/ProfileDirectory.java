package com.clinicemr.access;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.UUID;

import com.clinicemr.access.AccessDtos.SharedProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The people a phone number speaks for: profiles registered with that number at any clinic, plus profiles the
 * number's app account manages (e.g. a parent registered under another number). Only used after the number's
 * owner has approved, never for open lookups.
 */
@Component
public class ProfileDirectory {

    private final JdbcClient jdbc;

    public ProfileDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record ProfileRow(long id, UUID publicId, String name, String gender, LocalDate dob, String mrn) {
    }

    List<ProfileRow> forPhone(String phoneE164, long viewerClinicId) {
        return jdbc.sql("""
                SELECT p.id, p.public_id, p.name, p.gender, p.dob,
                       (SELECT mrn FROM patient_identifier pi WHERE pi.profile_id = p.id AND pi.clinic_id = :clinicId) AS mrn
                  FROM patient_profile p
                 WHERE p.deleted_at IS NULL AND p.merged_into_id IS NULL
                   AND (p.primary_phone_e164 = :phone
                        OR p.id IN (SELECT pa.profile_id FROM profile_access pa JOIN account a ON a.id = pa.account_id
                                     WHERE a.phone_e164 = :phone AND a.deleted_at IS NULL))
                 ORDER BY p.name
                """)
                .param("phone", phoneE164)
                .param("clinicId", viewerClinicId)
                .query(ProfileRow.class)
                .list();
    }

    List<SharedProfile> sharedProfiles(String phoneE164, long viewerClinicId) {
        return forPhone(phoneE164, viewerClinicId).stream()
                .map(p -> new SharedProfile(p.publicId(), p.name(), p.gender(),
                        p.dob() == null ? null : Period.between(p.dob(), LocalDate.now()).getYears(),
                        p.mrn() != null, p.mrn()))
                .toList();
    }
}
