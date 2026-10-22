package com.clinicemr.clinic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.clinicemr.shared.PhoneNumbers;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClinicService {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public ClinicService(JdbcClient jdbc, PasswordEncoder passwords, AuditService audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
    }

    public record ClinicView(long id, String name, String address, String city, String state, String pincode,
                             String phone, String status) {
    }

    public record StaffView(long staffId, String name, String email, List<String> roles, DoctorDetails doctor) {
    }

    /**
     * Self-service signup: creates the clinic (PENDING until a platform admin verifies it) and its first admin.
     * The admin may also be the clinic's doctor.
     */
    @Transactional
    public long registerClinic(ClinicDtos.RegisterClinicRequest req) {
        long clinicId = jdbc.sql("""
                INSERT INTO clinic (name, address, city, state, pincode, phone)
                VALUES (:name, :address, :city, :state, :pincode, :phone)
                RETURNING id
                """)
                .param("name", req.clinic().name().trim())
                .param("address", req.clinic().address())
                .param("city", req.clinic().city())
                .param("state", req.clinic().state())
                .param("pincode", req.clinic().pincode())
                .param("phone", optionalPhone(req.clinic().phone()))
                .query(Long.class)
                .single();

        Set<ClinicRole> roles = req.admin().doctor() != null
                ? Set.of(ClinicRole.ADMIN, ClinicRole.DOCTOR)
                : Set.of(ClinicRole.ADMIN);
        long staffId = createStaff(clinicId, req.admin().name(), req.admin().email(), req.admin().phone(),
                req.admin().password(), roles, req.admin().doctor());
        audit.staff(staffId, clinicId, "CLINIC_REGISTERED", "clinic", String.valueOf(clinicId), Map.of());
        return clinicId;
    }

    public ClinicView get(ClinicContext ctx) {
        return jdbc.sql("""
                SELECT id, name, address, city, state, pincode, phone, status FROM clinic WHERE id = :id
                """)
                .param("id", ctx.clinicId())
                .query(ClinicView.class)
                .single();
    }

    @Transactional
    public StaffView addStaff(ClinicContext ctx, ClinicDtos.AddStaffRequest req) {
        ctx.require(ClinicRole.ADMIN);
        if (req.roles().contains(ClinicRole.DOCTOR) && req.doctor() == null) {
            throw ApiException.badRequest("DOCTOR_DETAILS_REQUIRED",
                    "Doctors need a registration number, council and qualifications.");
        }

        Optional<Long> existing = jdbc.sql("SELECT id FROM staff_user WHERE lower(email) = lower(:email)")
                .param("email", req.email().trim())
                .query(Long.class)
                .optional();

        long staffId;
        if (existing.isPresent()) {
            // A doctor who already works at another clinic on the platform joins this one too.
            // Their existing doctor details are kept; only the person themselves may change them.
            // TODO: require the staff member to accept the invitation before the membership becomes active.
            staffId = existing.get();
            addRoles(ctx.clinicId(), staffId, req.roles());
            if (req.doctor() != null) {
                insertDoctorIfMissing(staffId, req.doctor());
            }
        } else {
            if (req.password() == null || req.password().length() < 10) {
                throw ApiException.badRequest("WEAK_PASSWORD", "New staff need a password of at least 10 characters.");
            }
            staffId = createStaff(ctx.clinicId(), req.name(), req.email(), req.phone(), req.password(),
                    req.roles(), req.doctor());
        }
        audit.staff(ctx.staffId(), ctx.clinicId(), "STAFF_ADDED", "staff_user", String.valueOf(staffId),
                Map.of("roles", req.roles()));
        return listStaff(ctx).stream().filter(s -> s.staffId() == staffId).findFirst().orElseThrow();
    }

    public List<StaffView> listStaff(ClinicContext ctx) {
        record Row(long staffId, String name, String email, String role, String registrationNo,
                   String registrationCouncil, String qualifications, String specialty) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT s.id AS staff_id, s.name, s.email, m.role,
                       d.registration_no, d.registration_council, d.qualifications, d.specialty
                  FROM clinic_member m
                  JOIN staff_user s ON s.id = m.staff_id
                  LEFT JOIN doctor d ON d.staff_id = s.id
                 WHERE m.clinic_id = :clinicId
                 ORDER BY s.name, m.role
                """)
                .param("clinicId", ctx.clinicId())
                .query(Row.class)
                .list();
        return rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(Row::staffId, java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .values().stream()
                .map(list -> {
                    Row r = list.get(0);
                    DoctorDetails doctor = r.registrationNo() == null ? null
                            : new DoctorDetails(r.registrationNo(), r.registrationCouncil(), r.qualifications(), r.specialty());
                    return new StaffView(r.staffId(), r.name(), r.email(), list.stream().map(Row::role).toList(), doctor);
                })
                .toList();
    }

    private long createStaff(long clinicId, String name, String email, String phone, String password,
                             Set<ClinicRole> roles, DoctorDetails doctor) {
        long staffId = jdbc.sql("""
                INSERT INTO staff_user (email, phone_e164, name, password_hash)
                VALUES (lower(:email), :phone, :name, :hash)
                RETURNING id
                """)
                .param("email", email.trim())
                .param("phone", optionalPhone(phone))
                .param("name", name.trim())
                .param("hash", passwords.encode(password))
                .query(Long.class)
                .single();
        addRoles(clinicId, staffId, roles);
        if (doctor != null) {
            insertDoctorIfMissing(staffId, doctor);
        }
        return staffId;
    }

    private void addRoles(long clinicId, long staffId, Set<ClinicRole> roles) {
        for (ClinicRole role : roles) {
            jdbc.sql("""
                    INSERT INTO clinic_member (clinic_id, staff_id, role) VALUES (:clinicId, :staffId, :role)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("clinicId", clinicId)
                    .param("staffId", staffId)
                    .param("role", role.name())
                    .update();
        }
    }

    private void insertDoctorIfMissing(long staffId, DoctorDetails d) {
        jdbc.sql("""
                INSERT INTO doctor (staff_id, registration_no, registration_council, qualifications, specialty)
                VALUES (:staffId, :regNo, :council, :qualifications, :specialty)
                ON CONFLICT (staff_id) DO NOTHING
                """)
                .param("staffId", staffId)
                .param("regNo", d.registrationNo().trim())
                .param("council", d.registrationCouncil().trim())
                .param("qualifications", d.qualifications().trim())
                .param("specialty", d.specialty())
                .update();
    }

    private static String optionalPhone(String phone) {
        return phone == null || phone.isBlank() ? null : PhoneNumbers.toE164(phone);
    }

    static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists.");
    }
}
