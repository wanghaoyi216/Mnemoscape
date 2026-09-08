package com.mnemoscape.auth.security;

import com.mnemoscape.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenBlacklistService} — R14 task 5.1。
 *
 * <p>重点验证：
 * <ul>
 *   <li>写入黑名单使用 {@code jwt:black:{jti}} 前缀，TTL 取 token 剩余有效期。</li>
 *   <li>已过期 / 缺 jti 的 token 直接 no-op，避免向 Redis 写入负 TTL。</li>
 *   <li>{@link TokenBlacklistService#isBlacklisted(String)} 在 key 存在时返回 true。</li>
 *   <li>Redis 异常吞掉，fail-open：宁可放行也不可让 auth 路径被 Redis 抖动打挂。</li>
 * </ul>
 */
class TokenBlacklistServiceTest {

    private static final String SECRET =
            "bW5lbW9zY2FwZS1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni1hbGdvcml0aG0tMjAyNQ==";

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;
    private JwtTokenProvider jwtTokenProvider;
    private TokenBlacklistService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        jwtTokenProvider = new JwtTokenProvider(SECRET, 3_600_000L, 86_400_000L);
        service = new TokenBlacklistService(redis, jwtTokenProvider);
    }

    @Test
    @DisplayName("revoke(token): writes jwt:black:{jti} with token's remaining TTL")
    void revokeByFullTokenWritesJti() {
        String access = jwtTokenProvider.generateAccessToken("u-1", "alice", "USER");
        String expectedJti = jwtTokenProvider.getJti(access);

        service.revoke(access);

        ArgumentCaptor<Duration> durationCap = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("jwt:black:" + expectedJti), eq("1"), durationCap.capture());
        long ttl = durationCap.getValue().toMillis();
        // TTL must be > 0 and <= access-token TTL (within 1s slack)
        assertTrue(ttl > 0, "TTL must be positive");
        assertTrue(ttl <= 3_600_000L, "TTL must not exceed the configured access-token TTL");
        assertTrue(ttl > 3_500_000L, "TTL must be close to full TTL for a freshly minted token");
    }

    @Test
    @DisplayName("revoke(token): blank token is silent no-op")
    void revokeBlankTokenIsNoop() {
        service.revoke(null);
        service.revoke("");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("revoke(jti, ttl): writes with the supplied TTL")
    void revokeByJtiAndTtl() {
        service.revoke("jti-abc-123", 30_000L);

        ArgumentCaptor<Duration> durationCap = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("jwt:black:jti-abc-123"), eq("1"), durationCap.capture());
        assertEquals(30_000L, durationCap.getValue().toMillis());
    }

    @Test
    @DisplayName("revoke(jti, ttl): non-positive ttl is skipped (no negative TTL writes)")
    void revokeByJtiWithZeroOrNegativeTtlIsNoop() {
        service.revoke("jti-1", 0L);
        service.revoke("jti-2", -5L);
        service.revoke(null, 1000L);
        service.revoke("", 1000L);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("isBlacklisted: returns true when key exists in Redis")
    void isBlacklistedTrueWhenKeyExists() {
        when(redis.hasKey("jwt:black:jti-1")).thenReturn(true);
        assertTrue(service.isBlacklisted("jti-1"));
    }

    @Test
    @DisplayName("isBlacklisted: returns false when key is absent")
    void isBlacklistedFalseWhenKeyMissing() {
        when(redis.hasKey("jwt:black:jti-1")).thenReturn(false);
        assertFalse(service.isBlacklisted("jti-1"));
    }

    @Test
    @DisplayName("isBlacklisted: null / blank input is always false (no Redis call)")
    void isBlacklistedBlankInputReturnsFalse() {
        assertFalse(service.isBlacklisted(null));
        assertFalse(service.isBlacklisted(""));
        verify(redis, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("isBlacklisted: Redis exception → false (fail-open)")
    void isBlacklistedSwallowsRedisErrors() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertFalse(service.isBlacklisted("jti-1"),
                "Redis outage must NOT 500 the auth path; we fail-open on read");
    }

    @Test
    @DisplayName("revoke: Redis exception swallowed, not propagated")
    void revokeSwallowsRedisErrors() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        // Must not throw.
        service.revoke("jti-1", 1000L);
        verify(valueOps).set(eq("jwt:black:jti-1"), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("revoke(token): malformed token is silently ignored")
    void revokeMalformedTokenSwallowed() {
        // "not.a.jwt" will fail validation in JwtTokenProvider.getJti — service must
        // swallow and continue, not bubble.
        service.revoke("not.a.jwt");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("revoke(token): expired token (TTL=0) → no-op, doesn't write a 0-TTL key")
    void revokeExpiredTokenIsNoop() {
        // Manually craft an already-expired JWT by using the same provider with a
        // negative TTL: that yields a token whose getRemainingMillis() returns 0.
        JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -1L, 86_400_000L);
        String expiredToken = expiredProvider.generateAccessToken("u-1", "alice", "USER");

        service.revoke(expiredToken);

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("isRefreshBlacklisted (interface default): returns false — refresh 黑名单走其他命名空间")
    void refreshBlacklistDefaultsToFalse() {
        // TokenBlacklistService implements JwtBlacklist but only owns the access-token
        // key space; refresh 黑名单继续由 RedisJwtBlacklist 维护。所以接口默认实现
        // 必须返回 false，避免误判。
        assertFalse(service.isRefreshBlacklisted("any-jti"));
    }
}