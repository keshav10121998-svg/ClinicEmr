package com.clinicemr.shared.security;

/** The signed-in super admin (the platform owner), from an admin token. Clinic and patient tokens never resolve to this. */
public record AdminContext(long adminId, String email) {
}
