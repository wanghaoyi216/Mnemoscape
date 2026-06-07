package com.mnemoscape.auth.security;

import com.mnemoscape.common.security.JwtBlacklist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 支撑的 JWT JTI 黑名单。
 *
 * <p>键格式：{@code jwt:blacklist:{jti}}，值固定写 "1"，TTL 设为 token 剩余有效期
 * — token 自然过期后 Redis 也自动清掉这一条，黑名单不会无限膨胀。
 *
 * <p>Redis 不可用时 {@link #isBlacklisted(String)} 默认返回 false（开放通过），
 * 避免依赖断开导致全站 401；这是有意识的可用性 vs 安全性权衡，生产强安全场景
 * 可改为返回 true 走 fail-closed 模式。
 */
@Component
public class RedisJwtBlacklist implements JwtBlacklist {

    private static final Logger log = LoggerFactory.getLogger(RedisJwtBlacklist.class);
    private static final String KEY_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redis;

    public RedisJwtBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try {
            Boolean exists = redis.hasKey(KEY_PREFIX + jti);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Redis blacklist check failed jti={} reason={}; defaulting to allow",
                    jti, e.getClass().getSimpleName());
            return false;
        }
    }

    /** 把 jti 写进黑名单，TTL 为 token 还剩多少毫秒；过期立即自动清理。 */
    public void revoke(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.error("Failed to revoke jti={} in Redis", jti, e);
        }
    }
}
