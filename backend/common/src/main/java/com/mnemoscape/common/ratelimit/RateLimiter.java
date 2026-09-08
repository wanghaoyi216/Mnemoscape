package com.mnemoscape.common.ratelimit;

/**
 * 限流器接口 —— {@link RateLimitAspect} 调它执行限流检查。
 *
 * <p>默认实现 {@link RedisSlidingWindowRateLimiter} 走 Redis Sorted Set + Lua，
 * 也有利于单测时换成内存实现。
 */
public interface RateLimiter {

    /**
     * 尝试从 bucket 中取一个令牌。返回 {@link Decision} 描述是否通过、剩余配额、
     * 多久后能重试。
     *
     * @param bucketKey 完整 Redis key（含 {@code ratelimit:} 前缀）
     * @param limit     窗口内最大请求数
     * @param windowSeconds  窗口长度（秒）
     * @return 决策结果
     */
    Decision tryAcquire(String bucketKey, int limit, int windowSeconds);

    /**
     * 限流决策。
     *
     * @param allowed 是否放行
     * @param remaining 窗口内剩余配额（仅当 {@link #allowed} 为 true 有意义）
     * @param retryAfterSeconds 距下次可用的秒数（仅当 {@link #allowed} 为 false 有意义）
     * @param degraded true 表示 Redis 不可达、限流器选择 fail-open 放行
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds, boolean degraded) {

        public static Decision allow(long remaining) {
            return new Decision(true, Math.max(0, remaining), 0, false);
        }

        public static Decision deny(long retryAfterSeconds) {
            return new Decision(false, 0, Math.max(1, retryAfterSeconds), false);
        }

        /** Redis 故障时 fail-open 放行 —— 标记 degraded 让监控可观测。 */
        public static Decision failOpen() {
            return new Decision(true, -1, 0, true);
        }
    }
}
