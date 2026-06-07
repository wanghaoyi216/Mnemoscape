package com.mnemoscape.common.ratelimit;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.exception.BizException;

/**
 * 限流失败异常 —— 由 {@link RateLimitAspect} 在 Redis Lua 返回 0 时抛出，
 * 配套的 {@code GlobalExceptionHandler} 应将其映射为 HTTP 429 + 标准 ApiResponse 信封。
 *
 * <p>继承 {@link BizException}（非 RuntimeException 直接继承）以纳入项目统一异常
 * 体系 —— 这样 429 响应会自动带上 {@code requestId / code / path} 等标准字段，
 * 不会和业务异常混在一起。
 */
public class RateLimitExceededException extends BizException {

    private final String bucket;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String bucket, long retryAfterSeconds, String message) {
        super(429, message);
        this.bucket = bucket;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getBucket() { return bucket; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }

    /**
     * 适配器：让 GlobalExceptionHandler 可以无损地写出 {@link ApiResponse} 信封。
     * 真正的 code 来自 {@code BizException.getCode()}。
     */
    public ApiResponse<Void> toApiResponse(String requestId) {
        return ApiResponse.error(getCode(), getMessage(), requestId);
    }
}
