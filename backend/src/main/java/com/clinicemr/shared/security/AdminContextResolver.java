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

/** Supplies {@link AdminContext} from a super admin token, checking the admin still exists. */
@Component
public class AdminContextResolver implements HandlerMethodArgumentResolver {

    private final JdbcClient jdbc;

    public AdminContextResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AdminContext.class);
    }

    @Override
    public AdminContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                        NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || !"admin".equals(jwt.getClaimAsString("typ"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in as the super admin first.");
        }
        long id = Long.parseLong(jwt.getSubject());
        String email = jdbc.sql("SELECT email FROM platform_admin WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in as the super admin first."));
        return new AdminContext(id, email);
    }
}
