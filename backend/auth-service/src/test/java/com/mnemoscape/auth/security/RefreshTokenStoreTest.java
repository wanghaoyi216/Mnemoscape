package com.mnemoscape.auth.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefreshTokenStore} (R14 task 5.1).
 *
 * <p>覆盖以下行为：
 * <ul>
 *   <li>{@code store(userId, jti, ttl)} → 调用 {@code opsForValue.set(key, "1", duration)}，TTL 正确。</li>
 *   <li>{@code isActive} 对 key 存在 / 不存在 / null / blank 输入的反应。</li>
 *   <li>{@code revoke} 单 key 删除。</li>
 *   <li>{@code revokeAllForUser} 用 SCAN（不是 KEYS）按 userId 分桶删除。</li>
 *   <li>Redis 异常吞掉，不向上抛。</li>
 * </ul>
 */
class RefreshTokenStoreTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;
    private RefreshTokenStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        store = new RefreshTokenStore(redis, 604_800_000L);
    }

    @Test
    @DisplayName("store: writes '1' to jwt:refresh:{userId}:{jti} with the supplied TTL")
    void storeWritesWithTtl() {
        store.store("u-alice", "jti-abc-123", 60_000L);

        ArgumentCaptor<Duration> durationCap = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("jwt:refresh:u-alice:jti-abc-123"), eq("1"), durationCap.capture());
        assertEquals(60_000L, durationCap.getValue().toMillis(),
                "TTL must round-trip as the supplied value");
    }

    @Test
    @DisplayName("store: ttl<=0 falls back to the configured default (7 days)")
    void storeFallsBackToDefaultTtl() {
        store.store("u-alice", "jti-abc-123", 0L);

        ArgumentCaptor<Duration> durationCap = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("jwt:refresh:u-alice:jti-abc-123"), eq("1"), durationCap.capture());
        assertEquals(604_800_000L, durationCap.getValue().toMillis(),
                "Non-positive ttl must fall back to the configured 7-day default");
    }

    @Test
    @DisplayName("store: blank userId or tokenId is a silent no-op")
    void storeWithBlankInputsIsNoop() {
        store.store(null, "jti-1", 1000L);
        store.store("", "jti-1", 1000L);
        store.store("u-1", null, 1000L);
        store.store("u-1", "", 1000L);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("isActive: true when key exists, false otherwise")
    void isActiveReflectsExistence() {
        when(redis.hasKey("jwt:refresh:u-alice:jti-1")).thenReturn(true);
        when(redis.hasKey("jwt:refresh:u-alice:jti-2")).thenReturn(false);

        assertTrue(store.isActive("u-alice", "jti-1"));
        assertFalse(store.isActive("u-alice", "jti-2"));
    }

    @Test
    @DisplayName("isActive: blank inputs return false without touching Redis")
    void isActiveWithBlankInputsReturnsFalse() {
        assertFalse(store.isActive(null, "jti"));
        assertFalse(store.isActive("u", null));
        assertFalse(store.isActive("", ""));
        verify(redis, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("isActive: Redis exceptions return false (fail-open) so we don't lock users out")
    void isActiveSwallowsRedisErrors() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertFalse(store.isActive("u-alice", "jti-1"),
                "Redis outage must not crash the auth path; the caller should treat as inactive");
    }

    @Test
    @DisplayName("revoke: deletes the single key, no-op on blank inputs")
    void revokeSingleKey() {
        store.revoke("u-alice", "jti-1");
        verify(redis).delete("jwt:refresh:u-alice:jti-1");

        store.revoke(null, "jti-1");
        store.revoke("u", null);
        verify(redis, times(1)).delete(anyString());
    }

    @Test
    @DisplayName("revokeAllForUser: SCAN+DEL only the userId bucket, returning the count")
    @SuppressWarnings("unchecked")
    void revokeAllForUserScansAndDeletes() {
        // Mock the SCAN cursor to yield two keys, then close.
        org.springframework.data.redis.connection.RedisConnection conn =
                mock(org.springframework.data.redis.connection.RedisConnection.class);
        org.springframework.data.redis.connection.RedisConnectionFactory factory =
                mock(org.springframework.data.redis.connection.RedisConnectionFactory.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(conn);

        Cursor<byte[]> cursor = mock(Cursor.class);
        when(conn.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                "jwt:refresh:u-alice:jti-A".getBytes(),
                "jwt:refresh:u-alice:jti-B".getBytes());
        when(redis.delete(any(java.util.Collection.class))).thenReturn(2L);

        long deleted = store.revokeAllForUser("u-alice");

        assertEquals(2L, deleted, "must return the number of actually-deleted keys");
        verify(redis).delete(java.util.List.of(
                "jwt:refresh:u-alice:jti-A",
                "jwt:refresh:u-alice:jti-B"));
    }

    @Test
    @DisplayName("revokeAllForUser: blank userId returns 0 without touching Redis")
    void revokeAllForUserBlankIsNoop() {
        assertEquals(0L, store.revokeAllForUser(null));
        assertEquals(0L, store.revokeAllForUser(""));
        verify(redis, never()).getConnectionFactory();
    }

    @Test
    @DisplayName("revokeAllForUser: Redis exceptions are swallowed and reported as 0")
    @SuppressWarnings("unchecked")
    void revokeAllForUserSwallowsErrors() {
        org.springframework.data.redis.connection.RedisConnectionFactory factory =
                mock(org.springframework.data.redis.connection.RedisConnectionFactory.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenThrow(new RuntimeException("redis down"));

        assertEquals(0L, store.revokeAllForUser("u-alice"),
                "Redis failure must not throw to the caller — auth path stays up");
    }

    @Test
    @DisplayName("listTokenIdsForUser: returns jtis parsed from SCAN'd keys")
    @SuppressWarnings("unchecked")
    void listTokenIdsForUser() {
        org.springframework.data.redis.connection.RedisConnection conn =
                mock(org.springframework.data.redis.connection.RedisConnection.class);
        org.springframework.data.redis.connection.RedisConnectionFactory factory =
                mock(org.springframework.data.redis.connection.RedisConnectionFactory.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(conn);

        Cursor<byte[]> cursor = mock(Cursor.class);
        when(conn.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                "jwt:refresh:u-alice:jti-X".getBytes(),
                "jwt:refresh:u-alice:jti-Y".getBytes());

        List<String> jtis = store.listTokenIdsForUser("u-alice");
        assertEquals(Set.of("jti-X", "jti-Y"), Set.copyOf(jtis),
                "must extract only the trailing jti segment, not the full key");
    }

    @Test
    @DisplayName("listTokenIdsForUser: returns empty list for blank userId without touching Redis")
    void listTokenIdsForUserBlankIsNoop() {
        assertEquals(Collections.emptyList(), store.listTokenIdsForUser(null));
        assertEquals(Collections.emptyList(), store.listTokenIdsForUser(""));
    }

    @Test
    @DisplayName("store: redis exception is swallowed, not propagated")
    void storeSwallowsRedisErrors() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        // 必须不抛 —— 写入失败不影响主流程
        store.store("u-alice", "jti-1", 1000L);
        verify(valueOps, times(1)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Integration: store → isActive → revoke reflects Redis state")
    void integrationLifecycle() {
        // 1) store -> isActive true
        store.store("u-alice", "jti-1", 1000L);
        when(redis.hasKey("jwt:refresh:u-alice:jti-1")).thenReturn(true);
        assertTrue(store.isActive("u-alice", "jti-1"));

        // 2) revoke -> isActive false
        store.revoke("u-alice", "jti-1");
        verify(redis).delete("jwt:refresh:u-alice:jti-1");

        // Force hasKey to return false after delete (simulating post-delete state)
        when(redis.hasKey("jwt:refresh:u-alice:jti-1")).thenReturn(false);
        assertFalse(store.isActive("u-alice", "jti-1"),
                "after revoke, the same jti must no longer be considered active");
    }

    @Test
    @DisplayName("TTL injected via @Value is observable through store-with-zero-ttl fallback")
    void ttlInjectionRespected() {
        // Construct a new store with a custom default TTL to prove the @Value binding
        // isn't accidentally hard-coded.
        RefreshTokenStore customStore = new RefreshTokenStore(redis, 1_000L);
        customStore.store("u-1", "jti-1", 0L);

        ArgumentCaptor<Duration> durationCap = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("jwt:refresh:u-1:jti-1"), eq("1"), durationCap.capture());
        assertEquals(1_000L, durationCap.getValue().toMillis(),
                "custom default TTL must be honored, not the hard-coded 7 days");
    }
}