package com.wanderly.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at /swagger-ui.html. Register or log in, copy the accessToken, click "Authorize"
 * and paste it; every request from the page then carries the Bearer token.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Wanderly API", version = "0.1.0",
                description = "Trip planning: events, places, day-by-day itineraries. Start with /api/auth/register, then Authorize."),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
