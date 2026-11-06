package com.clinicemr.shared.security;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.clinicemr.shared.error.ApiException;
import org.springframework.core.MethodParameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies {@link ClinicContext} to controller methods. Rejects the request unless the caller is a
 * signed-in staff member with a role in the clinic named by {@code X-Clinic-Id}, and that clinic is not suspended.
 * A clinic whose access has ended (or was never switched on) is read-only: anything but a read is refused with 402,
 * except making a link to view a prescription, which only reads.
 */
@Component
public class ClinicContextResolver implements HandlerMethodArgumentResolver {

    public static final String CLINIC_HEADER = "X-Clinic-Id";

    private final JdbcClient jdbc;

    public ClinicContextResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(ClinicContext.class);
    }

    @Override
    public ClinicContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                         NativeWebRequest request, WebDataBinderFactory binderFactory) {
        long staffId = currentStaffId();
        String header = request.getHeader(CLINIC_HEADER);
        if (header == null || header.isBlank()) {
            throw ApiException.badRequest("MISSING_CLINIC", "Header " + CLINIC_HEADER + " is required.");
        }
        long clinicId;
        try {
            clinicId = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("MISSING_CLINIC", "Header " + CLINIC_HEADER + " must be a clinic id.");
        }

        record Member(String role, LocalDate paidUntil) {
        }
        List<Member> members = jdbc.sql("""
                SELECT m.role, c.paid_until FROM clinic_member m
                  JOIN clinic c ON c.id = m.clinic_id
                  JOIN staff_user s ON s.id = m.staff_id
                 WHERE m.clinic_id = :clinicId AND m.staff_id = :staffId
                   AND c.status <> 'SUSPENDED' AND s.status = 'ACTIVE'
                """)
                .param("clinicId", clinicId)
                .param("staffId", staffId)
                .query(Member.class)
                .list();
        if (members.isEmpty()) {
            throw ApiException.forbidden("NOT_A_MEMBER", "You don't have access to this clinic.");
        }
        LocalDate paidUntil = members.get(0).paidUntil();
        if (changes(request) && !ClinicAccess.active(paidUntil, ClinicAccess.today())) {
            throw ClinicAccess.ended(paidUntil);
        }
        Set<ClinicRole> roleSet = EnumSet.noneOf(ClinicRole.class);
        members.forEach(m -> roleSet.add(ClinicRole.valueOf(m.role())));
        return new ClinicContext(staffId, clinicId, roleSet);
    }

    /** Anything but a read. Making a prescription link is a POST, but it only reads, so a clinic that is behind still gets it. */
    private static boolean changes(NativeWebRequest request) {
        HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
        if (http == null) {
            return false;
        }
        String method = http.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        return !http.getRequestURI().endsWith("/prescription-link");
    }

    public static long currentStaffId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || !"staff".equals(jwt.getClaimAsString("typ"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in as clinic staff first.");
        }
        return Long.parseLong(jwt.getSubject());
    }
}
