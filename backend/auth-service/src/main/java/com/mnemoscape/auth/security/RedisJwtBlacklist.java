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
 * <p>两套独立 key 空间：
 * <ul>
 *   <li>{@code jwt:blacklist:{jti}} —— access token 的黑名单；logout 时写入，
 *       {@link AuthService#logout(String)} 调用 {@link #revoke(String, long)}。</li>
 *   <li>{@code jwt:blacklist:refresh:{jti}} —— refresh token 的黑名单；refresh
 *       轮换成功后由 {@link AuthService#refreshToken(String)} 把旧 refresh token
 *       的 jti 写入，TTL 等于 token 剩余有效期。</li>
 * </ul>
 * 两个 key 空间独立查询，避免 logout 把 refresh token 误伤，也避免 refresh
 * 撤销影响正常 access token。
 *
 * <p>Redis 不可用时 {@link #isBlacklisted(String)} 默认返回 false（开放通过），
 * 避免依赖断开导致全站 401；这是有意识的可用性 vs 安全性权衡，生产强安全场景
 * 可改为返回 true 走 fail-closed 模式。
 */
@Component
public class RedisJwtBlacklist implements JwtBlacklist {

    private static final Logger log = LoggerFactory.getLogger(RedisJwtBlacklist.class);
    /** access token 黑名单前缀 */
    private static final String ACCESS_KEY_PREFIX = "jwt:blacklist:";
    /** refresh token 黑名单前缀（P0 R1.2：refresh token 轮换即撤销） */
    private static final String REFRESH_KEY_PREFIX = "jwt:blacklist:refresh:";

    private final StringRedisTemplate redis;

    public RedisJwtBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try {
            Boolean exists = redis.hasKey(ACCESS_KEY_PREFIX + jti);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Redis access-blacklist check failed jti={} reason={}; defaulting to allow",
                    jti, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean isRefreshBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try {
            Boolean exists = redis.hasKey(REFRESH_KEY_PREFIX + jti);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Redis refresh-blacklist check failed jti={} reason={}; defaulting to allow",
                    jti, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 把 access token 的 jti 写进黑名单，TTL 为 token 还剩多少毫秒；过期立即自动清理。
     */
    public void revoke(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(ACCESS_KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.error("Failed to revoke access jti={} in Redis", jti, e);
        }
    }

    /**
     * 把 refresh token 的 jti 写进 refresh 黑名单，TTL = refresh token 剩余有效期。
     * 由 {@link AuthService#refreshToken(String)} 在签发新 token 之后立即调用，
     * 实现"refresh token 一次性使用"语义（旧 refresh 立刻失效，防重放）。
     */
    public void revokeRefresh(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(REFRESH_KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.error("Failed to revoke refresh jti={} in Redis", jti, e);
        }
    }
}
