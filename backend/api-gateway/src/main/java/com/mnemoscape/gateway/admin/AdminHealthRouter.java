package com.mnemoscape.gateway.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Wires {@link HealthHandler} and {@link AuditHandler} onto internal-only paths so Spring Cloud
 * Gateway can {@code forward://} to them after the global filter chain has
 * authenticated the caller and {@link com.mnemoscape.gateway.filter.AdminGuardFilter}
 * has confirmed they are an admin.
 *
 * <p>The public paths are mapped in {@code application.yml} and forward to their
 * respective internal targets.
 */
@Configuration
public class AdminHealthRouter {

    /** Internal-only forward targets — never call these directly from outside. */
    public static final String INTERNAL_HEALTH_PATH = "/__internal/admin/health";
    public static final String INTERNAL_AUDIT_PATH = "/__internal/admin/audit/logs";

    @Bean
    public RouterFunction<ServerResponse> adminHealthRoutes(HealthHandler healthHandler, AuditHandler auditHandler) {
        return RouterFunctions.route(
                GET(INTERNAL_HEALTH_PATH).and(accept(APPLICATION_JSON)),
                healthHandler::handle
        ).andRoute(
                GET(INTERNAL_AUDIT_PATH).and(accept(APPLICATION_JSON)),
                auditHandler::handle
        );
    }
}

