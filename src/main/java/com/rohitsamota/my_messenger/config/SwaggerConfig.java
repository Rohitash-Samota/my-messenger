package com.rohitsamota.my_messenger.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "My Messenger API",
                version = "v1",
                description = "REST API for My Messenger"),
        security = @SecurityRequirement(name = SwaggerConfig.BEARER_AUTH))
@SecurityScheme(
        name = SwaggerConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class SwaggerConfig {
    public static final String BEARER_AUTH = "bearerAuth";
}
