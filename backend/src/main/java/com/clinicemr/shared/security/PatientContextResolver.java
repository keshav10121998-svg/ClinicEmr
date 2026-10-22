package com.clinicemr.shared.security;

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

/** Supplies {@link PatientContext} from a patient token. Staff tokens are rejected. */
@Component
public class PatientContextResolver implements HandlerMethodArgumentResolver {

    private final JdbcClient jdbc;

    public PatientContextResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(PatientContext.class);
    }

    @Override
    public PatientContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || !"patient".equals(jwt.getClaimAsString("typ"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in with your phone number first.");
        }
        long accountId = Long.parseLong(jwt.getSubject());
        String phone = jdbc.sql("SELECT phone_e164 FROM account WHERE id = :id AND deleted_at IS NULL AND status = 'ACTIVE'")
                .param("id", accountId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in again."));
        return new PatientContext(accountId, phone);
    }
}
