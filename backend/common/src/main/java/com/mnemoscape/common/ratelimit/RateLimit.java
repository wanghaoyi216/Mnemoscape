package com.mnemoscape.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 流量消峰注解 —— 基于 Redis 滑动窗口 + Lua 原子执行。
 *
 * <p>放在 controller 方法（或类）上后，{@link RateLimitAspect} 在调用前检查
 * 当前 key（{@link #key()} 或 {@code 类名#方法名}）在 {@link #windowSeconds()}
 * 滑动窗口内的请求数，超过 {@link #limit()} 时直接抛 {@link RateLimitExceededException}
 * （由 GlobalExceptionHandler 映射为 HTTP 429 + JSON 信封）。
 *
 * <p><b>Key 维度</b>：默认按"用户 / IP"区分 —— 已登录用户取
 * {@code SecurityContextHolder.getContext().getAuthentication().getName()}，
 * 匿名请求取 {@code X-Forwarded-For} 或 {@code remoteAddr}。
 * 用 {@link #dimension()} 切换 {@code USER} / {@code IP} / {@code GLOBAL}。
 *
 * <p><b>Fail-open 语义</b>：Redis 不可达时放行（与
 * {@code BypassOnFailureCacheManager} 行为对齐）—— 限流是"加速器"不是"刹车"，
 * Redis 挂了不该让所有请求都 5xx。
 *
 * <p>示例：
 * <pre>
 *   &#64;RateLimit(key = "auth:login", limit = 10, windowSeconds = 60)
 *   public ApiResponse&lt;LoginResult&gt; login(...) { ... }
 *
 *   // 同一方法按 IP 限流 5 rps
 *   &#64;RateLimit(key = "search:query", limit = 5, windowSeconds = 1, dimension = Dimension.IP)
 *   public ApiResponse&lt;SearchResult&gt; search(...) { ... }
 * </pre>
 *
 * @see RateLimitAspect
 * @see RateLimiter
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流 bucket key；省略时用 {@code 类名#方法名} 自动生成。 */
    String key() default "";

    /** 窗口内允许的最大请求数。 */
    int limit();

    /** 窗口长度（秒）。 */
    int windowSeconds();

    /** 限流维度 —— 按谁计数。 */
    Dimension dimension() default Dimension.USER_OR_IP;

    /** 命中 429 时返回给客户端的友好提示。 */
    String message() default "请求过于频繁，请稍后再试";

    enum Dimension {
        /** 已登录用户取 principal，未登录取 IP —— 适合混合端点（最常用）。 */
        USER_OR_IP,
        /** 仅按用户 ID；未登录请求会按 IP 兜底。 */
        USER,
        /** 仅按客户端 IP（X-Forwarded-For → remoteAddr）。 */
        IP,
        /** 全局共享 bucket —— 防服务端过载（CPU / 第三方 API 限流）。 */
        GLOBAL
    }
}
