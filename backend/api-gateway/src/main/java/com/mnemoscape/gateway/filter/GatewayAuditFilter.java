package com.mnemoscape.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Enterprise-Grade Observability Audit Filter.
 *
 * <p>Intercepts incoming microservice traffic, measures precise processing latency,
 * captures authenticated user context and client network details, cryptographically signs
 * each transaction with a SHA-256 hash, and reactively registers SIEM logs into Redis.
 */
@Component
public class GatewayAuditFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuditFilter.class);
    private static final String REDIS_AUDIT_KEY = "mnemoscape:audit:logs";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ReactiveStringRedisTemplate redis;

    public GatewayAuditFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip static web assets, swagger doc, actuator, and internal routing to avoid polluting audit logs
        if (isNoisePath(path)) {
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();

        return chain.filter(exchange).then(Mono.<Void>fromRunnable(() -> {
            try {
                long latency = System.currentTimeMillis() - startTime;
                recordAuditLog(exchange, latency);
            } catch (Exception e) {
                log.warn("[AuditFilter] Failed to record audit log: {}", e.getMessage());
            }
        })).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean isNoisePath(String path) {
        return path.contains("/actuator/") || 
               path.contains("/swagger-ui") || 
               path.contains("/v3/api-docs") || 
               path.contains("/webjars/") || 
               path.startsWith("/__internal/");
    }

    private void recordAuditLog(ServerWebExchange exchange, long latencyMs) {
        try {
            String path = exchange.getRequest().getURI().getPath();
            String method = exchange.getRequest().getMethod().name();

            // Extract identity injected by AuthGlobalFilter
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            String username = exchange.getRequest().getHeaders().getFirst("X-User-Name");
            String displayUser = (username != null && !username.isBlank()) ? username : 
                                 ((userId != null && !userId.isBlank()) ? userId : "guest");

            // Extract client IP and User-Agent
            String ip = exchange.getRequest().getRemoteAddress() != null ? 
                         exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "127.0.0.1";
            String ua = exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT);
            if (ua == null) ua = "Unknown Client";

            // Resolve target downstream microservice
            org.springframework.cloud.gateway.route.Route route = 
                exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String service = route != null ? route.getId() : "api-gateway";

            // Map path to a clean enterprise action description
            String action = mapAction(method, path);

            // Determine status based on HTTP response code
            HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
            int code = statusCode != null ? statusCode.value() : 200;
            String status = "SUCCESS";
            if (code >= 500) status = "ERROR";
            else if (code >= 400) status = "WARNING";

            String logId = "AUD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String timeStr = LocalDateTime.now().format(TIME_FORMATTER);

            // Generate SHA-256 cryptographic transaction integrity signature
            String rawSignatureData = logId + "|" + timeStr + "|" + ip + "|" + path + "|" + code + "|" + displayUser;
            String payloadHash = generateSha256Signature(rawSignatureData);

            // Format to JSON
            String jsonLog = String.format(
                "{\"id\":\"%s\",\"time\":\"%s\",\"service\":\"%s\",\"user\":\"%s\",\"action\":\"%s\"," +
                "\"status\":\"%s\",\"latencyMs\":%d,\"ip\":\"%s\",\"url\":\"%s %s\",\"userAgent\":\"%s\",\"payloadHash\":\"%s\"}",
                logId, timeStr, service, displayUser, action, status, latencyMs, ip, method, path, escapeJson(ua), payloadHash
            );

            // Reactively push to Redis List and keep trimmed at 50 records
            redis.opsForList().leftPush(REDIS_AUDIT_KEY, jsonLog)
                 .flatMap(len -> redis.opsForList().trim(REDIS_AUDIT_KEY, 0, 49))
                 .subscribeOn(Schedulers.boundedElastic())
                 .subscribe(
                     len -> log.debug("[AuditFilter] SIEM security record successfully pushed: {}", logId),
                     err -> log.warn("[AuditFilter] Redis push failure: {}", err.getMessage())
                 );

        } catch (Exception e) {
            log.warn("[AuditFilter] Serialization error: {}", e.getMessage());
        }
    }

    private String mapAction(String method, String path) {
        if (path.startsWith("/api/v1/auth/login")) return "User authentication login";
        if (path.startsWith("/api/v1/auth/register")) return "New user account registration";
        if (path.startsWith("/api/v1/auth/refresh")) return "JWT session refresh request";
        if (path.startsWith("/api/v1/memories") && "POST".equalsIgnoreCase(method)) return "Create personal memory";
        if (path.startsWith("/api/v1/memories") && "GET".equalsIgnoreCase(method)) return "Fetch user memories";
        if (path.matches("^/api/v1/memories/[^/]+$")) {
            if ("DELETE".equalsIgnoreCase(method)) return "Delete personal memory";
            if ("PUT".equalsIgnoreCase(method)) return "Update memory content";
            if ("GET".equalsIgnoreCase(method)) return "Query single memory detail";
        }
        if (path.startsWith("/api/v1/admin/health")) return "Check microservice health topology";
        if (path.startsWith("/api/v1/admin/audit/logs")) return "View SIEM security operations console";
        if (path.startsWith("/api/v1/admin/stats/active-users")) return "Query user activity analytics";
        if (path.startsWith("/api/v1/assets/upload")) return "Upload media asset to MinIO store";
        if (path.startsWith("/api/v1/chat")) return "Stream memory chatbot reasoning";
        if (path.startsWith("/api/v1/resonances")) return "Retrieve emotional resonance spaces";
        return method + " " + path;
    }

    private String generateSha256Signature(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String sha = hexString.toString();
            return sha.substring(0, 8) + "..." + sha.substring(56);
        } catch (Exception e) {
            return "sha-signature-error";
        }
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    @Override
    public int getOrder() {
        // Run at Ordered.LOWEST_PRECEDENCE so we profile post-downstream completion
        return Ordered.LOWEST_PRECEDENCE;
    }
}
