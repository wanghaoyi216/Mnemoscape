package com.mnemoscape.memory.config;

import com.mnemoscape.common.web.MdcContextFilter;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Forwards request-scoped trace headers on every outbound Feign call.
 *
 * <p>Inbound side: the gateway tags every request with {@code X-Correlation-Id}
 * (and {@code X-User-Id} / {@code X-User-Name}); {@link MdcContextFilter} on
 * the receiving service pulls those values into SLF4J's MDC.
 *
 * <p>Outbound side (this bean): when {@code memory-service} calls
 * {@code ai-service} via Feign, we read the current thread's MDC and re-inject
 * the same headers on the outgoing request — closing the trace loop so a 500
 * surfacing in {@code ai-service} can be grepped by the very same
 * {@code requestId} the end user is told to quote.
 *
 * <p>Safety: missing MDC entries (e.g. Feign call from a {@code @Scheduled} job
 * with no inbound context) are silently skipped. We never invent a fresh id
 * here — the receiving filter does that when the header is absent, which
 * keeps "no upstream trace" cases identifiable in logs.
 */
@Configuration
public class FeignCorrelationConfig {

    @Bean
    public RequestInterceptor correlationPropagatingInterceptor() {
        return template -> {
            copyMdcToHeader(template, MdcContextFilter.MDC_CORRELATION_ID, MdcContextFilter.CORRELATION_ID_HEADER);
            copyMdcToHeader(template, MdcContextFilter.MDC_USER_ID, MdcContextFilter.USER_ID_HEADER);
            copyMdcToHeader(template, MdcContextFilter.MDC_USER_NAME, MdcContextFilter.USER_NAME_HEADER);
        };
    }

    private static void copyMdcToHeader(feign.RequestTemplate template, String mdcKey, String header) {
        String value = MDC.get(mdcKey);
        if (value == null || value.isBlank()) {
            return;
        }
        // Only set when the upstream caller hasn't already provided it. Feign's
        // RequestTemplate#header(name, values) appends rather than replaces, and
        // duplicate Correlation-Id headers would split MDC reads downstream.
        if (template.headers().containsKey(header)) {
            return;
        }
        template.header(header, value);
    }
}
