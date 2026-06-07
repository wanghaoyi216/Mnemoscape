package com.mnemoscape.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * 基于 Redis Sorted Set + Lua 的滑动窗口限流器。
 *
 * <p><b>原理</b>：每个 bucket 在 Redis 里是一个 ZSET，member 是请求时间戳（毫秒），
 * score 也是时间戳。每次请求走一次 Lua 原子脚本：
 * <ol>
 *   <li>移除 score &lt; now - windowMs 的旧 member（出窗）</li>
 *   <li>读 ZCARD 看当前窗口计数</li>
 *   <li>若 ≥ limit 返回 0（拒绝）；否则 ZADD 当前时间戳并返回 1（通过）</li>
 * </ol>
 *
 * <p><b>为什么用 Lua</b>：上面三步必须在 Redis 单线程上原子完成，否则并发下
 * ZCARD + ZADD 之间会被别的请求插队，限流失效。
 *
 * <p><b>Fail-open</b>：Redis 抛 {@link RedisConnectionFailureException} 或其他
 * {@link DataAccessException} 时返回 {@link Decision#failOpen()} —— 限流是
 * "加速器"不是"刹车"，不能因为 Redis 挂了就让所有请求 5xx。和
 * {@code BypassOnFailureCacheManager} 同套设计哲学。
 */
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);

    /** Bucket key 前缀 —— 避免污染业务 key 命名空间。 */
    public static final String KEY_PREFIX = "ratelimit:";

    private static final String LUA = """
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
              return {0, math.max(0, retryMs)}
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, windowMs + 5000)
            return {1, limit - count - 1}
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(LUA, List.class);
    }

    @Override
    public Decision tryAcquire(String bucketKey, int limit, int windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            return Decision.allow(limit);
        }
        String fullKey = KEY_PREFIX + bucketKey;
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        // member 用 now + 4 位随机，避免同毫秒并发 ZADD 去重
        String member = now + "-" + (int) (Math.random() * 10000);
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(
                    script,
                    List.of(fullKey),
                    String.valueOf(now),
                    String.valueOf(windowMs),
                    String.valueOf(limit),
                    member);
            if (result == null || result.size() < 2) {
                return Decision.failOpen();
            }
            long allowed = result.get(0);
            long meta = result.get(1);
            if (allowed == 1) {
                return Decision.allow(meta);
            }
            // meta 是毫秒数 → 转秒
            return Decision.deny((meta + 999) / 1000);
        } catch (DataAccessException e) {
            log.warn("[ratelimit] Redis unavailable, fail-open bucket={} reason={}",
                    bucketKey, e.getClass().getSimpleName());
            return Decision.failOpen();
        } catch (Exception e) {
            log.warn("[ratelimit] Unexpected error, fail-open bucket={} reason={}",
                    bucketKey, e.toString());
            return Decision.failOpen();
        }
    }
}
