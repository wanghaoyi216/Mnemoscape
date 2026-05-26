package com.mnemoscape.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class RequestCorrelationFilter implements GlobalFilter, Ordered {
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_ATTR = "correlationId";
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = resolveCorrelationId(exchange.getRequest().getHeaders());
        HttpMethod httpMethod = exchange.getRequest().getMethod();
        String method = httpMethod != null ? httpMethod.name() : "UNKNOWN";
        String path = exchange.getRequest().getURI().getPath();
        long startTime = System.currentTimeMillis();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();
        exchange.getAttributes().put(CORRELATION_ID_ATTR, correlationId);
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);

        return chain.filter(exchange.mutate().request(request).build())
                .doOnError(ex -> log.warn("Gateway request error method={} path={} correlationId={} reason={}",
                        method, path, correlationId, ex.getClass().getSimpleName()))
                .doFinally(signalType -> {
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    long duration = System.currentTimeMillis() - startTime;
                    String statusValue = status != null ? String.valueOf(status.value()) : "NA";
                    log.info("Gateway {} {} -> {} ({} ms) correlationId={}",
                            method, path, statusValue, duration, correlationId);
                });
    }

    private String resolveCorrelationId(HttpHeaders headers) {
        String headerValue = headers.getFirst(CORRELATION_ID_HEADER);
        if (headerValue != null) {
            String trimmed = headerValue.trim();
            if (!trimmed.isBlank() && trimmed.length() <= 128) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
