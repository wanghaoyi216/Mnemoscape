package com.mnemoscape.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * API Gateway 全局限流过滤器。
 *
 * <p><b>设计说明</b>：
 * <ul>
 *   <li>基于 Redis Sorted Set + Lua 脚本实现滑动窗口限流，原子操作保证并发安全</li>
 *   <li>限流维度：IP + 路径前缀，按业务风险分级配置阈值</li>
 *   <li>Fail-open：Redis 不可用时放行，避免单点故障拖垮整个网关</li>
 *   <li>执行顺序 -150：在 AuthGlobalFilter(-100) 之前、RequestCorrelationFilter(-200) 之后
 *       —— 不依赖 traceId，但要在鉴权前拦截保护后端</li>
 * </ul>
 *
 * <p><b>限流规则</b>：
 * <pre>
 *   /api/v1/auth/*       → 10 次/60s  防止登录暴力破解
 *   /api/v1/reconstruct/ → 5 次/300s  AI 场景重建成本高
 *   /api/v1/memories/*   → 30 次/60s  正常浏览上限
 *   其它                 → 100 次/60s 默认兜底
 * </pre>
 */
@Component
public class GlobalRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GlobalRateLimitFilter.class);

    /** 顺序：-150 → Auth 鉴权前、Correlation 后 */
    public static final int ORDER = -150;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List> rateLimitScript;

    // 滑动窗口 Lua：ZREM 旧数据 → ZCARD 计数 → ZADD 记录，三步原子执行
    private static final String RATE_LIMIT_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            local cutoff = now - windowMs
            redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
            local count = redis.call('ZCARD', key)
            if count >= limit then
              local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
              local oldestScore = tonumber(oldest[2]) or now
              local retryMs = oldestScore + windowMs - now
              return {0, tostring(math.max(0, retryMs))}
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, math.ceil((windowMs + 5000) / 1000))
            return {1, tostring(limit - count - 1)}
            """;

    public GlobalRateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>(RATE_LIMIT_LUA, List.class);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 健康检查 / WebSocket / 内部探活不限流，避免影响监控
        if (isHealthCheckPath(path)) {
            return chain.filter(exchange);
        }

        String clientIp = resolveClientIp(request);
        RateLimitConfig config = matchRateLimitConfig(path);
        String bucketKey = "gateway:ratelimit:" + config.keyPrefix() + ":" + clientIp;

        long now = System.currentTimeMillis();
        long windowMs = config.windowSeconds() * 1000L;
        // member 加随机后缀，避免同毫秒并发请求 ZADD 互踩去重
        String member = now + "-" + (int) (Math.random() * 10000);

        return redisTemplate.execute(
                rateLimitScript,
                List.of(bucketKey),
                List.of(
                        String.valueOf(now),
                        String.valueOf(windowMs),
                        String.valueOf(config.limit()),
                        member
                )
        )
        .next() // 取第一条返回
        .timeout(Duration.ofSeconds(2))
        .flatMap(result -> {
            if (result == null || result.size() < 2) {
                // Redis 返回不完整，fail-open 放行
                log.warn("[GatewayRateLimit] Redis returned empty, allowing request");
                return chain.filter(exchange);
            }
            // Lua 返回的是 {allowed, retryMs}，allowed=1 通过，0 限流
            long allowed = toLong(result.get(0));
            if (allowed == 0L) {
                long retryMs = toLong(result.get(1));
                return writeRateLimitResponse(exchange, retryMs);
            }
            return chain.filter(exchange);
        })
        .onErrorResume(ex -> {
            // Redis 连接异常等，fail-open 不阻断主流程
            log.warn("[GatewayRateLimit] Rate limit check failed, allowing: {}", ex.getMessage());
            return chain.filter(exchange);
        });
    }

    /** Lua 返回的元素可能是 Long 或 String，统一转 long。 */
    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 返回 429 限流响应，附带 Retry-After 头让客户端知道何时重试。
     */
    private Mono<Void> writeRateLimitResponse(ServerWebExchange exchange, long retryMs) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        long retrySeconds = Math.max(1, (retryMs + 999) / 1000);
        response.getHeaders().add(HttpHeaders.RETRY_AFTER, String.valueOf(retrySeconds));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"error\":\"Rate limit exceeded\",\"retryAfterSeconds\":" + retrySeconds + "}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    /**
     * 根据请求路径匹配对应的限流配置，越敏感的路径阈值越低。
     */
    private RateLimitConfig matchRateLimitConfig(String path) {
        if (path.startsWith("/api/v1/auth/")) {
            return new RateLimitConfig("auth", 10, 60);
        }
        if (path.startsWith("/api/v1/reconstruct/")) {
            return new RateLimitConfig("reconstruct", 5, 300);
        }
        if (path.startsWith("/api/v1/memories/")) {
            return new RateLimitConfig("memories", 30, 60);
        }
        return new RateLimitConfig("default", 100, 60);
    }

    /**
     * 解析客户端真实 IP，优先取 X-Forwarded-For / X-Real-IP，避免代理穿透。
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String xri = request.getHeaders().getFirst("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        return request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "0.0.0.0";
    }

    private boolean isHealthCheckPath(String path) {
        return path.startsWith("/actuator/")
                || path.equals("/health")
                || path.startsWith("/ws/")
                || path.startsWith("/api/v1/admin/health");
    }

    /**
     * 限流配置：keyPrefix 用作 Redis key 前缀，limit 是窗口内允许的请求数，
     * windowSeconds 是窗口大小（秒）。
     */
    private record RateLimitConfig(String keyPrefix, int limit, int windowSeconds) {}
}
