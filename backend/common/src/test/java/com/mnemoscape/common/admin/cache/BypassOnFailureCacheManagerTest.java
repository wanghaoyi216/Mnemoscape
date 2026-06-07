package com.mnemoscape.common.admin.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.SerializationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the BypassCache decorator chain (admin-dashboard task 1.10
 * / Requirements 14.3).
 *
 * <p>Behaviours under test:
 * <ul>
 *   <li>delegate {@code getCache} returning {@code null} → manager returns
 *       {@code null} (caller falls back / fails fast on misconfig);</li>
 *   <li>{@link Cache#get(Object)} throws Redis-related exceptions →
 *       decorator returns {@code null} so {@code @Cacheable} treats it as
 *       miss;</li>
 *   <li>{@code put / evict / clear / putIfAbsent / evictIfPresent /
 *       invalidate} swallow Redis-related exceptions silently;</li>
 *   <li>SerializationException is treated identically to connection
 *       failures (R14.3 covers schema-drift bypass);</li>
 *   <li>non-Redis exceptions still propagate (we don't want to mask bugs).</li>
 * </ul>
 */
class BypassOnFailureCacheManagerTest {

    private CacheManager delegate;
    private Cache underlying;
    private BypassOnFailureCacheManager manager;

    @BeforeEach
    void setUp() {
        delegate = mock(CacheManager.class);
        underlying = mock(Cache.class);
        when(underlying.getName()).thenReturn("admin.test");
        when(delegate.getCache("admin.test")).thenReturn(underlying);
        when(delegate.getCacheNames()).thenReturn(List.of("admin.test"));
        manager = new BypassOnFailureCacheManager(delegate);
    }

    @Test
    void getCacheReturnsNullWhenDelegateHasNoBinding() {
        when(delegate.getCache("missing")).thenReturn(null);
        assertNull(manager.getCache("missing"),
                "manager must mirror delegate's null result so misconfig surfaces fast");
    }

    @Test
    void getCacheNamesDelegates() {
        assertEquals(List.of("admin.test"), manager.getCacheNames());
    }

    @Test
    void delegateAccessor() {
        assertSame(delegate, manager.getDelegate());
    }

    @Test
    void getOnConnectionFailureReturnsNull() {
        when(underlying.get("k"))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        Cache wrapped = manager.getCache("admin.test");
        assertNotNull(wrapped);
        assertNull(wrapped.get("k"), "connection failure must downgrade to miss");
    }

    @Test
    void getOnRedisSystemExceptionReturnsNull() {
        when(underlying.get("k"))
                .thenThrow(new RedisSystemException("malformed reply", new RuntimeException()));
        Cache wrapped = manager.getCache("admin.test");
        assertNull(wrapped.get("k"));
    }

    @Test
    void getOnSerializationExceptionReturnsNull() {
        when(underlying.get("k"))
                .thenThrow(new SerializationException("schema drift", new RuntimeException()));
        Cache wrapped = manager.getCache("admin.test");
        assertNull(wrapped.get("k"));
    }

    @Test
    void getTypedOnFailureReturnsNull() {
        when(underlying.get("k", String.class))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        Cache wrapped = manager.getCache("admin.test");
        assertNull(wrapped.get("k", String.class));
    }

    @Test
    void putSwallowsConnectionFailure() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(underlying).put("k", "v");
        Cache wrapped = manager.getCache("admin.test");
        // Must not throw — caller continues normally.
        wrapped.put("k", "v");
        verify(underlying, times(1)).put("k", "v");
    }

    @Test
    void putIfAbsentReturnsNullOnConnectionFailure() {
        when(underlying.putIfAbsent("k", "v"))
                .thenThrow(new RedisSystemException("down", new RuntimeException()));
        Cache wrapped = manager.getCache("admin.test");
        assertNull(wrapped.putIfAbsent("k", "v"));
    }

    @Test
    void evictSwallowsConnectionFailure() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(underlying).evict("k");
        Cache wrapped = manager.getCache("admin.test");
        wrapped.evict("k"); // must not throw
        verify(underlying, times(1)).evict("k");
    }

    @Test
    void evictIfPresentReturnsFalseOnConnectionFailure() {
        when(underlying.evictIfPresent("k"))
                .thenThrow(new RedisSystemException("down", new RuntimeException()));
        Cache wrapped = manager.getCache("admin.test");
        assertFalse(wrapped.evictIfPresent("k"));
    }

    @Test
    void clearSwallowsConnectionFailure() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(underlying).clear();
        Cache wrapped = manager.getCache("admin.test");
        wrapped.clear(); // must not throw
        verify(underlying, times(1)).clear();
    }

    @Test
    void invalidateReturnsFalseOnConnectionFailure() {
        when(underlying.invalidate())
                .thenThrow(new RedisConnectionFailureException("down"));
        Cache wrapped = manager.getCache("admin.test");
        assertFalse(wrapped.invalidate());
    }

    @Test
    void nonRedisExceptionStillPropagates() {
        // Defensive: an unexpected runtime exception MUST NOT be silently
        // swallowed — that would mask real bugs.
        when(underlying.get("k"))
                .thenThrow(new IllegalStateException("not a redis problem"));
        Cache wrapped = manager.getCache("admin.test");
        assertThrows(IllegalStateException.class, () -> wrapped.get("k"));
    }

    @Test
    void delegatePassThroughOnHappyPath() {
        Cache.ValueWrapper hit = () -> "value";
        when(underlying.get("k")).thenReturn(hit);
        Cache wrapped = manager.getCache("admin.test");
        assertSame(hit, wrapped.get("k"));
    }

    @Test
    void getNameDelegates() {
        assertEquals("admin.test", manager.getCache("admin.test").getName());
    }

    @Test
    void constructorRejectsNullDelegate() {
        assertThrows(NullPointerException.class,
                () -> new BypassOnFailureCacheManager(null));
    }
}
