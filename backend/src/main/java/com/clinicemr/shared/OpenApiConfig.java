package com.clinicemr.shared;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.ClinicContextResolver;

@Configuration
public class OpenApiConfig {

    static {
        // ClinicContext is resolved from the token and header, not from request parameters
        org.springdoc.core.utils.SpringDocUtils.getConfig().addRequestWrapperToIgnore(ClinicContext.class);
    }

    @Bean
    OpenAPI api() {
        return new OpenAPI()
                .info(new Info().title("Clinic EMR API").version("v1"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    /** Shows the X-Clinic-Id header on every operation that acts for a clinic, and hides the resolved parameter. */
    @Bean
    OperationCustomizer clinicHeader() {
        return (operation, handlerMethod) -> {
            boolean needsClinic = java.util.Arrays.stream(handlerMethod.getMethodParameters())
                    .anyMatch(p -> p.getParameterType().equals(ClinicContext.class));
            if (needsClinic) {
                operation.addParametersItem(new HeaderParameter()
                        .name(ClinicContextResolver.CLINIC_HEADER)
                        .required(true)
                        .description("Clinic you are acting for")
                        .schema(new StringSchema()));
            }
            return operation;
        };
    }
}
