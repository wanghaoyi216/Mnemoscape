package com.mnemoscape.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 写路径幂等守卫 —— 让 POST/PUT 接口能识别用户的"双击"或"网络重试"。
 *
 * <p><b>触发条件</b>：客户端在 HTTP 头里加 {@code Idempotency-Key: <任意字符串>}。
 * 服务端第一次见到这个 key 时执行真正的写操作（{@link Supplier}），把响应体
 * 缓存到 Redis 24h。后续 24h 内带同一 key 的请求直接返回缓存响应，不重写。
 *
 * <p><b>为什么需要它</b>：用户场景是"快速连点提交按钮"或"网络抖动让客户端重发"。
 * 没幂等时会出现：用户看到一条记忆，刷新后又多出一条。Mnemoscape 的"重建场景
 * 一次 30s + 调用 LLM 烧钱" 场景下重复创建成本极高。
 *
 * <p><b>设计取舍</b>：
 * <ul>
 *   <li>Redis 存储（非 DB）—— 24h 后自然失效，不需要 cron 清理</li>
 *   <li>用 SETNX 原子操作做"令牌获取" —— 多实例并发时只有 1 个 winner</li>
 *   <li>失败时透传异常 —— 不要因为幂等机制把业务错误藏起来</li>
 *   <li>Redis 挂时降级为透传（fail-open） —— 与 CacheManager 同套哲学</li>
 * </ul>
 *
 * <p><b>缓存 key 命名</b>：{@code idem:<key>} —— 与 rate-limit 的 {@code ratelimit:}
 * 前缀错开便于排查。
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);
    private static final String KEY_PREFIX = "idem:";
    private static final String LOCK_PREFIX = "idem:lock:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(10);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public IdempotencyGuard(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper) {
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 在幂等守卫下执行写操作。
     *
     * @param key    客户端 Idempotency-Key
     * @param action 真正执行业务的 supplier
     * @param <T>    响应体类型
     * @return 响应体 —— 第一次执行时是 action 的结果，后续是缓存的副本
     */
    public <T> T executeOnce(String key, Class<T> responseType, Supplier<T> action) {
        String fullKey = KEY_PREFIX + key;
        String lockKey = LOCK_PREFIX + key;
        // 1. 查缓存
        Optional<T> cached = readCached(fullKey, responseType);
        if (cached.isPresent()) {
            log.debug("[idem] cache hit key={} (skipped re-execution)", key);
            return cached.get();
        }

        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.warn("[idem] Redis template unavailable key={} (fail-open)", key);
            return executeAndCacheBestEffort(fullKey, key, action, null);
        }

        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(lockKey, "processing", PROCESSING_TTL));
        } catch (Exception e) {
            log.warn("[idem] redis lock acquire failed key={} reason={} (fail-open)",
                    key, e.toString());
            return executeAndCacheBestEffort(fullKey, key, action, null);
        }
        if (!acquired) {
            return readCached(fullKey, responseType)
                    .orElseThrow(() -> BizException.conflict(
                            "重复请求正在处理中，请稍后使用同一个 Idempotency-Key 重试"));
        }

        try {
            // 2. 真正执行（这里可能跑几秒 —— 场景重建、LLM 调用、向量索引等）
            return executeAndCacheBestEffort(fullKey, key, action, redis);
        } finally {
            try {
                redis.delete(lockKey);
            } catch (Exception e) {
                log.warn("[idem] lock cleanup failed key={} reason={}", key, e.toString());
            }
        }
    }

    private <T> Optional<T> readCached(String fullKey, Class<T> type) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(fullKey);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("[idem] cache read failed key={} reason={} (treating as miss)",
                    fullKey, e.toString());
            return Optional.empty();
        }
    }

    private <T> T executeAndCacheBestEffort(String fullKey, String rawKey, Supplier<T> action,
                                            StringRedisTemplate redis) {
        T result = action.get();
        StringRedisTemplate targetRedis = redis == null ? redisProvider.getIfAvailable() : redis;
        if (targetRedis == null) {
            return result;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            targetRedis.opsForValue().set(fullKey, json, DEFAULT_TTL);
        } catch (JsonProcessingException e) {
            log.warn("[idem] serialize failed key={} reason={}", rawKey, e.toString());
        } catch (Exception e) {
            log.warn("[idem] redis cache write failed key={} reason={} (fail-open)",
                    rawKey, e.toString());
        }
        return result;
    }
}
