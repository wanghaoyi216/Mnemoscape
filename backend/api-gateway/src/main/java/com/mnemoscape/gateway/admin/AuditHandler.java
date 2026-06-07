package com.mnemoscape.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.gateway.filter.RequestCorrelationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Enterprise Audit SIEM Endpoint Handler.
 *
 * <p>Retrieves the latest 30 signed API audit transactions from the centralized Redis list
 * and reactively maps them into typed objects for the admin observability panel.
 */
@Component
public class AuditHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditHandler.class);
    private static final String REDIS_AUDIT_KEY = "mnemoscape:audit:logs";
    
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AuditHandler(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Reactively retrieve, map, and return the latest security audits.
     */
    @SuppressWarnings("rawtypes")
    public Mono<ServerResponse> handle(ServerRequest request) {
        String requestId = request.exchange().getAttributeOrDefault(
                RequestCorrelationFilter.CORRELATION_ID_ATTR, "unknown").toString();

        log.debug("[AuditHandler] Security audit list request received correlationId={}", requestId);

        return redis.opsForList().range(REDIS_AUDIT_KEY, 0, 29)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, Map.class);
                    } catch (Exception e) {
                        log.warn("[AuditHandler] Failed to deserialize audit record: {}", e.getMessage());
                        return Map.of("error", "Deserialization error", "raw", json);
                    }
                })
                .collectList()
                .flatMap(logs -> {
                    ApiResponse<List> envelope = ApiResponse.<List>builder()
                            .code(200)
                            .message("OK")
                            .data(logs)
                            .requestId(requestId)
                            .build();

                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(envelope);
                })
                .onErrorResume(err -> {
                    log.error("[AuditHandler] Reactive read failed", err);
                    ApiResponse<List> errEnvelope = ApiResponse.<List>builder()
                            .code(500)
                            .message("Failed to query Redis SIEM logs: " + err.getMessage())
                            .requestId(requestId)
                            .build();
                    return ServerResponse.status(500)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(errEnvelope);
                });
    }
}
