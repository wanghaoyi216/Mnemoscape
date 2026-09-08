package com.mnemoscape.auth.security;

import com.mnemoscape.common.security.JwtBlacklist;
import com.mnemoscape.common.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Access token 主动失效服务 —— R14 黑名单语义的对外门面。
 *
 * <p>与 {@link RedisJwtBlacklist}（底层 Redis 适配器）相比，本类是面向"业务流程"
 * 的封装：传入 access token 原文，自动算 TTL、写黑名单。底层 key 空间为
 * {@code jwt:black:{jti}}，与 R6 已存在的 {@code jwt:blacklist:{jti}} 命名空间
 * 并行存在；后者用于 R6 引入的"轮换即撤销 refresh"语义，本类接管 access token
 * 的 logout / 强制下线。两套空间互不干扰，由调用方按需选择。
 *
 * <p>典型场景：
 * <ul>
 *   <li>{@link #revoke(String)} —— 用户主动 logout，把当前 access token 的 jti
 *       写黑名单，TTL 取 token 剩余有效期（已过期 token 直接 no-op，避免无效 Redis 写入）。</li>
 *   <li>{@link #revoke(String, long)} —— 管理员踢人 / 密码重置后批量撤销，给定 jti 与 TTL。</li>
 *   <li>{@link #isRevoked(String)} —— {@link com.mnemoscape.common.security.JwtAuthFilter}
 *       拿到 claims 后调用，决定是否放行。</li>
 * </ul>
 *
 * <p>本类同时实现 {@link JwtBlacklist} 接口，可以直接替换 {@link RedisJwtBlacklist}
 * 注入到 {@link com.mnemoscape.common.security.JwtAuthFilter} —— 后续如果决定
 * 统一 key 命名空间（{@code jwt:blacklist:} → {@code jwt:black:}），只需改
 * SecurityConfig 一行注入。
 */
@Service
public class TokenBlacklistService implements JwtBlacklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    /** access token 黑名单 key 前缀 —— R14 命名空间，与 R6 的 jwt:blacklist:* 并行。 */
    public static final String BLACK_KEY_PREFIX = "jwt:black:";

    private final StringRedisTemplate redis;
    private final JwtTokenProvider jwtTokenProvider;

    public TokenBlacklistService(StringRedisTemplate redis, JwtTokenProvider jwtTokenProvider) {
        this.redis = redis;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * 从 access token 原文里取出 jti 与剩余有效期，写黑名单。
     * 用于 AuthService.logout() 这种"传入完整 token"的场景。
     */
    public void revoke(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            String jti = jwtTokenProvider.getJti(accessToken);
            long ttl = jwtTokenProvider.getRemainingMillis(accessToken);
            if (jti == null || jti.isBlank() || ttl <= 0) {
                log.debug("revoke no-op: missing jti or already expired ttl={}", ttl);
                return;
            }
            revoke(jti, ttl);
        } catch (Exception e) {
            log.warn("Failed to parse access token for revocation: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 直接按 jti + TTL 写入黑名单。给"管理员踢人"、"密码重置"等已知 jti 场景使用。
     *
     * @param jti        access token 的 JWT ID
     * @param ttlMillis  key 有效期（毫秒）。建议传 token 剩余有效期；≤0 表示 no-op。
     */
    public void revoke(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(BLACK_KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.error("Failed to revoke access jti={} ttlMs={} reason={}",
                    jti, ttlMillis, e.getClass().getSimpleName(), e);
        }
    }

    /** access token 的 jti 是否已被撤销。 */
    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try {
            Boolean exists = redis.hasKey(BLACK_KEY_PREFIX + jti);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Blacklist lookup failed jti={} reason={}; defaulting to allow",
                    jti, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * refresh token 黑名单仍由 {@link RedisJwtBlacklist#isRefreshBlacklisted(String)}
     * 维护（key 前缀 {@code jwt:blacklist:refresh:}）。本类不重写，
     * 直接走接口默认 NOOP —— 若调用方注入本类到 JwtAuthFilter，应同时配套
     * 注入 {@code RedisJwtBlacklist} 以覆盖 refresh 黑名单语义。
     */
    @Override
    public boolean isRefreshBlacklisted(String jti) {
        return false;
    }
}