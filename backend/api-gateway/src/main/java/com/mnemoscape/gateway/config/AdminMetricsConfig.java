package com.mnemoscape.gateway.config;

import com.mnemoscape.common.admin.metrics.AdminMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicitly registers {@link AdminMetrics} in the gateway context.
 *
 * <p>{@link AdminMetrics} lives in {@code com.mnemoscape.common.admin.metrics}
 * and is annotated {@code @Component}, but the gateway's
 * {@link com.mnemoscape.gateway.GatewayApplication} does not include
 * {@code com.mnemoscape.common} in its {@code scanBasePackages} (intentionally
 * — that package contains servlet-only beans like {@code MdcContextFilter} and
 * {@code OpenApiConfig} that would conflict with the reactive gateway runtime).
 * This {@link Configuration} surgically registers the one common bean the
 * gateway actually needs, fed by the {@link MeterRegistry} the
 * {@code spring-boot-starter-actuator} dependency auto-configures (Prometheus
 * registry per {@code application.yml}).
 */
@Configuration
public class AdminMetricsConfig {

    /**
     * Build the shared {@link AdminMetrics} decorator from the auto-configured
     * Micrometer registry. Used by {@code AdminGuardFilter} to bump the
     * {@code mnemoscape.admin.authz.rejects} counter on 401/403 outcomes
     * (Requirement 3.5).
     */
    @Bean
    public AdminMetrics adminMetrics(MeterRegistry meterRegistry) {
        return new AdminMetrics(meterRegistry);
    }
}
