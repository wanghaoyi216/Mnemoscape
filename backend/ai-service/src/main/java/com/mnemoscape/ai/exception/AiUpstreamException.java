package com.mnemoscape.ai.exception;

/**
 * 表示与 NVIDIA / OpenAI 兼容上游对话时不可用：未配置 key、限流、5xx、超时…
 *
 * <p>controller / SSE 层接到该异常一律转成结构化错误：
 * <pre>
 *   HTTP 502/503
 *   { code: "AI_UPSTREAM_UNAVAILABLE", requestId, detail }
 * </pre>
 *
 * 这种"显式失败"取代了过去 ChatReasoner 模板"伪装成 AI 回复"的方式，
 * 让前端能给出真实的"AI 暂不可用"提示。
 */
public class AiUpstreamException extends RuntimeException {

    public enum Reason {
        /** 服务端没有配置真实 NVIDIA_API_KEY（占位符仍存在） */
        MISSING_KEY,
        /** 上游返回 401 / 403 — key 失效 */
        AUTHENTICATION,
        /** 上游 5xx / 429 / 网络抖动 */
        UPSTREAM_ERROR,
        /** 调用超时 */
        TIMEOUT,
        /** 本地令牌桶限流命中（{@link com.mnemoscape.ai.service.AiCacheService#acquireOrThrow}），
         *  避免把 429 打到 NVIDIA 上 */
        RATE_LIMITED,
        /** 其他未分类的失败 */
        UNKNOWN;
    }

    private final Reason reason;

    public AiUpstreamException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AiUpstreamException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }

    /** 推荐的 HTTP 状态码：401/403 → 502（鉴权问题），其余 → 503。 */
    public int httpStatus() {
        return switch (reason) {
            case MISSING_KEY, AUTHENTICATION -> 502;
            case RATE_LIMITED -> 429;
            default -> 503;
        };
    }
}
