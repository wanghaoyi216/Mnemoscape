package com.mnemoscape.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared OpenAPI 3.0 configuration.
 * <p>Each Mnemoscape microservice exposes its own /v3/api-docs and /swagger-ui.html
 * endpoint with bearer-token auth pre-registered.  Service identity is taken from
 * {@code spring.application.name} so we keep one declaration for all of them.</p>
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:mnemoscape-service}")
    private String serviceName;

    @Bean
    public OpenAPI mnemoscapeOpenAPI() {
        final String securitySchemeName = "bearer-jwt";
        return new OpenAPI()
                .info(new Info()
                        .title("Mnemoscape — " + serviceName)
                        .description("Mnemoscape is an AI-driven personal memory museum. "
                                + "Users describe a memory in natural language; the platform reconstructs it as "
                                + "an explorable 3D scene, models drift over time, and surfaces emotional resonance "
                                + "with other memories. This document describes the public HTTP contract for the "
                                + serviceName + " microservice.")
                        .version("v1")
                        .contact(new Contact()
                                .name("Mnemoscape Platform Team")
                                .email("platform@mnemoscape.local"))
                        .license(new License()
                                .name("Internal — All rights reserved")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Paste the JWT access token issued by /api/v1/auth/login.")));
    }
}
