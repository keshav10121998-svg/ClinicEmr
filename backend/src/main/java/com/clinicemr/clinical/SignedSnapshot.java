package com.clinicemr.clinical;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The complete visit as signed, including the patient, clinic and doctor details at that moment, so the record
 * and its printed prescription can be reproduced exactly even if those details change later.
 */
public record SignedSnapshot(
        UUID encounterId,
        int version,
        String visitType,
        Instant startedAt,
        Instant signedAt,
        String amendmentReason,
        PatientInfo patient,
        ClinicInfo clinic,
        DoctorInfo doctor,
        EncounterContent content) {

    public record PatientInfo(UUID publicId, String mrn, String name, String gender, LocalDate dob,
                              boolean dobEstimated, Integer ageYears) {
    }

    public record ClinicInfo(long id, String name, String address, String city, String state, String pincode,
                             String phone) {
    }

    public record DoctorInfo(long staffId, String name, String qualifications, String registrationNo,
                             String registrationCouncil, String specialty) {
    }
}
