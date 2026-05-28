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
 * Wires {@link HealthHandler} onto an internal-only path so Spring Cloud
 * Gateway can {@code forward://} to it after the global filter chain has
 * authenticated the caller and {@link com.mnemoscape.gateway.filter.AdminGuardFilter}
 * has confirmed they are an admin.
 *
 * <p>The public path {@code /api/v1/admin/health} is mapped to
 * {@code forward:/__internal/admin/health} in {@code application.yml}. Using
 * {@code forward://} (instead of binding the handler directly to
 * {@code /api/v1/admin/health} via {@code @RestController} or a top-level
 * router) is essential: Spring Cloud Gateway's {@code GlobalFilter}s run on
 * the {@code FilteringWebHandler} dispatcher path, so binding a handler on
 * a routed path through the gateway pipeline is the only way to ensure
 * {@code AdminGuardFilter} runs before the aggregator does (R3.2 + R18.4).
 *
 * <p>The internal path itself is intentionally outside the public
 * {@code /api/v1/...} prefix so it cannot be reached without going through
 * the public route.
 */
@Configuration
public class AdminHealthRouter {

    /** Internal-only forward target — never call this directly from outside. */
    public static final String INTERNAL_HEALTH_PATH = "/__internal/admin/health";

    @Bean
    public RouterFunction<ServerResponse> adminHealthRoutes(HealthHandler handler) {
        return RouterFunctions.route(
                GET(INTERNAL_HEALTH_PATH).and(accept(APPLICATION_JSON)),
                handler::handle
        );
    }
}
