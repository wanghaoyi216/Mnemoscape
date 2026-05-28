package com.mnemoscape.gateway.filter;

import com.mnemoscape.common.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final JwtTokenProvider jwtTokenProvider;
    private final ReactiveStringRedisTemplate redis;

    private static final List<String> PUBLIC_EXACT_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/assets/static/resources",
            "/actuator",
            "/v3/api-docs",
            "/swagger-ui.html"
    );

    private static final List<String> PUBLIC_PREFIX_PATHS = List.of(
            "/ws/",
            "/actuator/",
            "/api/v1/assets/static/",
            "/v3/api-docs/",
            "/swagger-ui/",
            "/swagger-resources/",
            "/webjars/"
    );

    public AuthGlobalFilter(
            @Value("${mnemoscape.jwt.secret}") String jwtSecret,
            @Value("${mnemoscape.jwt.access-token-expiration}") long accessTokenExp,
            @Value("${mnemoscape.jwt.refresh-token-expiration}") long refreshTokenExp,
            ReactiveStringRedisTemplate redis) {
        log.info("AuthGlobalFilter initialized with accessTokenExp={}", accessTokenExp);
        this.jwtTokenProvider = new JwtTokenProvider(jwtSecret, accessTokenExp, refreshTokenExp);
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            // 公共路径无需 token；但若客户端带了有效 token，"尽力解析"并注入身份头，
            // 让下游服务（如 asset-service）可以区分"匿名访问"vs"该用户的私有视图"。
            // 解析失败 / 无 token / 黑名单命中都视为匿名，绝不阻塞公共路径。
            return chain.filter(maybeAttachIdentity(exchange));
        }

        String correlationId = resolveCorrelationId(exchange);
        String token = extractBearerToken(exchange.getRequest().getHeaders());
        if (token == null) {
            log.debug("Missing authorization for path={} correlationId={}", path, correlationId);
            return unauthorized(exchange, "Missing authorization", correlationId);
        }

        Claims claims;
        try {
            claims = jwtTokenProvider.validateToken(token);
        } catch (Exception e) {
            log.warn("JWT validation failed for path={} correlationId={} reason={}", path, correlationId, e.getClass().getSimpleName());
            log.debug("JWT validation error for path={}", path, e);
            return unauthorized(exchange, "Invalid token", correlationId);
        }

        String jti = claims.getId();
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate()
                .header("X-User-Id", claims.getSubject());
        String username = claims.get("username", String.class);
        if (username != null && !username.isBlank()) {
            requestBuilder.header("X-User-Name", username);
        }
        // Strict role resolution: only the case-sensitive literal "ADMIN" promotes
        // to ADMIN; everything else (including "USER", missing claim, or any
        // unexpected value) is mapped to USER. R2.2 / R2.6: the header is only
        // injected when the JWT is valid; the 401 path above returns before this
        // point and therefore never attaches X-User-Role.
        requestBuilder.header("X-User-Role", resolveRole(claims.get("role", String.class)));
        ServerHttpRequest modified = requestBuilder.build();
        ServerWebExchange forwarded = exchange.mutate().request(modified).build();

        if (jti == null || jti.isBlank()) {
            return chain.filter(forwarded);
        }

        // 反应式查 Redis 黑名单；Redis 异常时 fail-open（与 auth-service 同策略），
        // 避免缓存层抖动导致全站 401。
        return redis.hasKey(BLACKLIST_PREFIX + jti)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        log.info("Rejecting revoked jti={} path={} correlationId={}", jti, path, correlationId);
                        return unauthorized(exchange, "Session revoked", correlationId);
                    }
                    return chain.filter(forwarded);
                })
                .onErrorResume(err -> {
                    log.warn("Redis blacklist check failed for jti={} path={} reason={}; allowing through",
                            jti, path, err.getClass().getSimpleName());
                    return chain.filter(forwarded);
                });
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_EXACT_PATHS.contains(path)
                || PUBLIC_PREFIX_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * 公共路径"尽力解析"模式：带有效 token 时把身份头注入到下游 request；
     * 没带 / 解析失败 / 黑名单命中都不阻塞，只是不注入身份头。
     */
    private ServerWebExchange maybeAttachIdentity(ServerWebExchange exchange) {
        String token = extractBearerToken(exchange.getRequest().getHeaders());
        if (token == null) return exchange;
        try {
            Claims claims = jwtTokenProvider.validateToken(token);
            ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.getSubject());
            String username = claims.get("username", String.class);
            if (username != null && !username.isBlank()) {
                builder.header("X-User-Name", username);
            }
            // Same strict mapping as the authenticated path so downstream services
            // see a consistent X-User-Role header semantics regardless of which
            // branch attached the identity.
            builder.header("X-User-Role", resolveRole(claims.get("role", String.class)));
            return exchange.mutate().request(builder.build()).build();
        } catch (Exception ignored) {
            return exchange;
        }
    }

    /**
     * Map the raw JWT {@code role} claim to the case-sensitive header value used
     * by downstream services. Only the literal string "ADMIN" maps to "ADMIN";
     * any other value (including null, blank, "user", "Admin", or unknown roles)
     * maps to "USER". This mirrors the resolution done in
     * {@code com.mnemoscape.common.security.JwtAuthFilter} so the gateway header
     * and the in-service security context cannot disagree.
     */
    private static String resolveRole(String roleClaim) {
        return "ADMIN".equals(roleClaim) ? "ADMIN" : "USER";
    }

    private String extractBearerToken(HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null) {
            return null;
        }
        String trimmed = authHeader.trim();
        if (trimmed.length() < 7) {
            return null;
        }
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private String resolveCorrelationId(ServerWebExchange exchange) {
        Object correlationId = exchange.getAttribute(RequestCorrelationFilter.CORRELATION_ID_ATTR);
        return correlationId != null ? correlationId.toString() : "unknown";
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message, String correlationId) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(RequestCorrelationFilter.CORRELATION_ID_HEADER, correlationId);
        String body = "{\"code\":401,\"message\":\"" + message + "\",\"data\":null,\"requestId\":\"" + correlationId + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
