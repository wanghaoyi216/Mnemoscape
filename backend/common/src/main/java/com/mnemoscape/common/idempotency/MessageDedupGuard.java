package com.mnemoscape.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * RabbitMQ consumer 幂等守卫。
 *
 * <p>RabbitMQ 默认是 at-least-once：consumer 崩溃、连接抖动、手动重投都会让同一
 * 领域事件再次到达。该守卫用 Redis SETNX 做一个短处理租约，成功处理后把 key
 * 延长为"已处理"状态；重复投递看到 key 已存在就直接跳过。
 *
 * <p>Redis 不可用时 fail-open：宁可重复执行一次幂等业务逻辑，也不让 Redis 抖动
 * 把消息消费主路径打断。
 */
@Component
public class MessageDedupGuard {

    private static final Logger log = LoggerFactory.getLogger(MessageDedupGuard.class);

    private static final String KEY_PREFIX = "mq:dedup:";
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(10);
    private static final Duration PROCESSED_TTL = Duration.ofDays(7);

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public MessageDedupGuard(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /**
     * 尝试开始处理一条消息。
     *
     * @return empty 表示重复消息，调用方应直接 ack/return；present 表示可以处理。
     */
    public Optional<Lease> tryBegin(String eventType, String eventId, String fallbackKey) {
        String key = buildKey(eventType, eventId, fallbackKey);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.debug("[mq-dedup] Redis template unavailable, fail-open eventType={}", eventType);
            return Optional.of(Lease.failOpen(key));
        }
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "processing", PROCESSING_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return Optional.of(new Lease(key, redis, true));
            }
            log.info("[mq-dedup] duplicate event skipped eventType={} key={}", eventType, key);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[mq-dedup] Redis acquire failed eventType={} key={} reason={} (fail-open)",
                    eventType, key, e.toString());
            return Optional.of(Lease.failOpen(key));
        }
    }

    private static String buildKey(String eventType, String eventId, String fallbackKey) {
        String material = eventId == null || eventId.isBlank()
                ? "natural:" + String.valueOf(fallbackKey)
                : "id:" + eventId;
        return KEY_PREFIX + eventType + ":" + sha256(material);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static final class Lease {
        private final String key;
        private final StringRedisTemplate redis;
        private final boolean backedByRedis;

        private Lease(String key, StringRedisTemplate redis, boolean backedByRedis) {
            this.key = key;
            this.redis = redis;
            this.backedByRedis = backedByRedis;
        }

        private static Lease failOpen(String key) {
            return new Lease(key, null, false);
        }

        public void markProcessed() {
            if (!backedByRedis) return;
            try {
                redis.opsForValue().set(key, "done", PROCESSED_TTL);
            } catch (Exception e) {
                log.warn("[mq-dedup] mark processed failed key={} reason={}", key, e.toString());
            }
        }

        public void clear() {
            if (!backedByRedis) return;
            try {
                redis.delete(key);
            } catch (Exception e) {
                log.warn("[mq-dedup] clear lease failed key={} reason={}", key, e.toString());
            }
        }
    }
}
