package com.mnemoscape.common.admin.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;

/**
 * {@link CacheManager} decorator that wraps every {@link Cache} from the
 * delegate (typically a {@code RedisCacheManager}) in a {@link BypassCache} so
 * that any Redis outage degrades to "no cache" rather than surfacing a 5xx to
 * the caller (Requirements 14.3).
 *
 * <p>Each service registers this manager as the {@code @Primary CacheManager}
 * bean while keeping the original {@code RedisCacheManager} as the delegate
 * (named bean). The cache names, per-cache TTLs, and serialization rules are
 * defined on the delegate; this decorator only adds failure tolerance and
 * does not introduce its own cache configuration.
 *
 * <p>If the delegate's {@link CacheManager#getCache(String)} returns
 * {@code null} (cache name not configured), this decorator returns
 * {@code null} as well — Spring's caching machinery will then either fail-fast
 * or, if {@code spring.cache.cache-names} permits dynamic cache creation,
 * fall back to its default behaviour. This decorator deliberately does not
 * synthesize an in-memory fallback cache because that could mask
 * misconfiguration where a cache name is simply mistyped.
 */
public class BypassOnFailureCacheManager implements CacheManager {

    private final CacheManager delegate;

    /**
     * Construct the decorator.
     *
     * @param delegate the underlying cache manager (typically a
     *                 {@code RedisCacheManager}); must not be {@code null}
     */
    public BypassOnFailureCacheManager(CacheManager delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate cache manager must not be null");
        }
        this.delegate = delegate;
    }

    @Override
    public Cache getCache(String name) {
        Cache underlying = delegate.getCache(name);
        if (underlying == null) {
            return null;
        }
        return new BypassCache(underlying);
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    /**
     * Expose the wrapped delegate for diagnostics or tests. Production code
     * should prefer using the manager directly via {@link CacheManager}.
     *
     * @return the underlying cache manager passed to the constructor
     */
    public CacheManager getDelegate() {
        return delegate;
    }
}
