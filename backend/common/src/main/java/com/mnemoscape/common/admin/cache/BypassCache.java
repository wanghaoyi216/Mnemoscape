package com.mnemoscape.common.admin.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.SerializationException;

import java.util.concurrent.Callable;

/**
 * Cache decorator that transparently degrades to "no cache" when the underlying
 * Redis backing store is unreachable or returns malformed data
 * (Requirements 14.3).
 *
 * <p>Behaviour summary:
 * <ul>
 *   <li>{@link #get(Object)} / {@link #get(Object, Class)} / {@link #get(Object, Callable)}
 *       — on {@link RedisConnectionFailureException}, {@link RedisSystemException},
 *       or {@link SerializationException}, a WARN line is emitted and
 *       {@code null} (cache miss) is returned, so Spring's {@code @Cacheable}
 *       machinery falls through to the underlying method body.</li>
 *   <li>{@link #put(Object, Object)} / {@link #putIfAbsent(Object, Object)}
 *       — failures are silently swallowed after a WARN line; the caller
 *       continues normally with the value already in hand.</li>
 *   <li>{@link #evict(Object)} / {@link #evictIfPresent(Object)} / {@link #clear()}
 *       — failures are silently swallowed after a WARN line; the caller is
 *       not informed because evicting from an unreachable cache is logically
 *       equivalent to a no-op for the next read (which will also bypass).</li>
 * </ul>
 *
 * <p>The {@link #getNativeCache()} delegate is exposed verbatim so consumers
 * who need direct access (e.g. metrics) still see the underlying Redis
 * connection.
 *
 * <p>This class is package-private intentionally — instances are created only
 * by {@link BypassOnFailureCacheManager#getCache(String)}; callers should never
 * wrap their own caches manually.
 */
final class BypassCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(BypassCache.class);

    private final Cache delegate;

    BypassCache(Cache delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate cache must not be null");
        }
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        try {
            return delegate.get(key);
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("get", e);
            return null;
        }
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        try {
            return delegate.get(key, type);
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("get", e);
            return null;
        }
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        try {
            return delegate.get(key, valueLoader);
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("get", e);
            // Fall back to the loader directly so the caller still receives a value.
            try {
                return valueLoader.call();
            } catch (Exception loaderEx) {
                throw new ValueRetrievalException(key, valueLoader, loaderEx);
            }
        }
    }

    @Override
    public void put(Object key, Object value) {
        try {
            delegate.put(key, value);
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("put", e);
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        try {
            return delegate.putIfAbsent(key, value);
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("putIfAbsent", e);
            return null;
        }
    }

    @Override
    public void evict(Object key) {
        try {
            delegate.evict(key);
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("evict", e);
        }
    }

    @Override
    public boolean evictIfPresent(Object key) {
        try {
            return delegate.evictIfPresent(key);
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("evictIfPresent", e);
            return false;
        }
    }

    @Override
    public void clear() {
        try {
            delegate.clear();
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("clear", e);
        }
    }

    @Override
    public boolean invalidate() {
        try {
            return delegate.invalidate();
        } catch (RedisConnectionFailureException | RedisSystemException
                 | SerializationException e) {
            warn("invalidate", e);
            return false;
        }
    }

    private void warn(String op, Throwable cause) {
        log.warn("Redis cache {} failed; bypassing. cache={} cause={}",
                op, delegate.getName(), cause.getClass().getSimpleName());
    }
}
