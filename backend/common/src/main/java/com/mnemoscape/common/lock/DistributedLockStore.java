package com.mnemoscape.common.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁工具 — 基于 Redisson。
 *
 * <p>用法:
 * <pre>
 * lockStore.execute("memory:" + memoryId, 5, () -> {
 *     // 临界区代码
 *     return result;
 * });
 * </pre>
 *
 * <p><b>Fail-open 语义</b>:RedissonClient 不可用(未装配/Redis 故障)时,
 * 直接执行业务逻辑不阻塞 — 与项目其他 fail-open(ratelimit/idempotency)对齐。
 * 限流/幂等是"加速器不是刹车",分布式锁同理:Redis 挂了不让所有写操作 5xx。
 *
 * <p><b>leaseTime</b>:锁的自动释放时间(秒),防止持锁进程崩溃导致死锁。
 * 业务逻辑必须在 leaseTime 内完成。建议设比业务最长耗时略长(默认 10s)。
 *
 * <p><b>waitTime</b>:获取锁的等待时间。0 = 不等待,拿不到立即返回
 * (适用于"并发冲突时直接拒绝/换一个"的场景,如漂流瓶捡取)。
 */
@Component
public class DistributedLockStore {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockStore.class);

    /** 默认锁持有时间(秒),覆盖大多数业务临界区。 */
    private static final long DEFAULT_LEASE_SECONDS = 10;
    /** 默认获取锁等待时间(秒),0 = 不等待。 */
    private static final long DEFAULT_WAIT_SECONDS = 0;

    private final ObjectProvider<RedissonClient> redissonProvider;

    public DistributedLockStore(ObjectProvider<RedissonClient> redissonProvider) {
        this.redissonProvider = redissonProvider;
    }

    /**
     * 在分布式锁保护下执行业务,使用默认 waitTime=0 / leaseTime=10s。
     *
     * @param lockKey  锁 key(建议格式:业务:资源ID,如 "memory:abc123")
     * @param action   临界区逻辑
     * @return 业务返回值;拿不到锁返回 null(调用方自行处理冲突)
     */
    public <T> T execute(String lockKey, Supplier<T> action) {
        return execute(lockKey, DEFAULT_WAIT_SECONDS, DEFAULT_LEASE_SECONDS, action);
    }

    /**
     * 在分布式锁保护下执行业务,自定义 waitTime / leaseTime。
     *
     * @param lockKey       锁 key
     * @param waitSeconds   获取锁等待时间(秒),0 = 不等待
     * @param leaseSeconds  锁持有时间(秒),到时自动释放
     * @param action        临界区逻辑
     * @return 业务返回值;拿不到锁返回 null
     */
    public <T> T execute(String lockKey, long waitSeconds, long leaseSeconds, Supplier<T> action) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            // fail-open:Redisson 未装配,直接执行
            log.debug("[lock] Redisson unavailable, fail-open for key={}", lockKey);
            return action.get();
        }
        RLock lock = client.getLock("lock:" + lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[lock] acquire failed (held by others) key={}", lockKey);
                return null;
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[lock] interrupted while acquiring key={}", lockKey);
            return null;
        } finally {
            if (acquired) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    // 锁已过期自动释放(IllegalMonitorStateException),忽略
                    log.debug("[lock] unlock failed key={} (likely expired): {}", lockKey, e.toString());
                }
            }
        }
    }
}
