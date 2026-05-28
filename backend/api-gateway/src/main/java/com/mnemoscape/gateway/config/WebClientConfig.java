package com.mnemoscape.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient infrastructure for the gateway's own outbound calls (notably the
 * {@code /api/v1/admin/health} aggregator in
 * {@link com.mnemoscape.gateway.admin.HealthHandler}).
 *
 * <p>The gateway already speaks {@code lb://service-name} for its routes; we
 * register a separate {@link WebClient.Builder} marked
 * {@link LoadBalanced @LoadBalanced} so any handler running inside the
 * gateway VM can call downstream services by their Nacos name (for example
 * {@code http://auth-service/actuator/health}) without hard-coding host /
 * port and without going through Spring Cloud Gateway's routing pipeline.
 *
 * <p>The bean is intentionally only a builder (rather than a fully built
 * {@link WebClient}) so each consumer can layer its own per-call timeouts,
 * codecs, or filters on top — for example {@code HealthHandler} attaches a
 * 2s response timeout per probe.
 */
@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
