package com.clinicemr.shared.security;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.clinicemr.shared.error.ApiException;
import org.springframework.core.MethodParameter;
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

        List<String> roles = jdbc.sql("""
                SELECT m.role FROM clinic_member m
                  JOIN clinic c ON c.id = m.clinic_id
                  JOIN staff_user s ON s.id = m.staff_id
                 WHERE m.clinic_id = :clinicId AND m.staff_id = :staffId
                   AND c.status <> 'SUSPENDED' AND s.status = 'ACTIVE'
                """)
                .param("clinicId", clinicId)
                .param("staffId", staffId)
                .query(String.class)
                .list();
        if (roles.isEmpty()) {
            throw ApiException.forbidden("NOT_A_MEMBER", "You don't have access to this clinic.");
        }
        Set<ClinicRole> roleSet = EnumSet.noneOf(ClinicRole.class);
        roles.forEach(r -> roleSet.add(ClinicRole.valueOf(r)));
        return new ClinicContext(staffId, clinicId, roleSet);
    }

    public static long currentStaffId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || !"staff".equals(jwt.getClaimAsString("typ"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in as clinic staff first.");
        }
        return Long.parseLong(jwt.getSubject());
    }
}
