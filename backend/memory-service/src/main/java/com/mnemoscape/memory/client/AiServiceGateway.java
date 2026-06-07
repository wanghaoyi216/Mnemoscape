package com.mnemoscape.memory.client;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.memory.model.dto.EntityExtractionResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 给 ai-service Feign 调用统一加熔断 + 重试的网关层。
 *
 * <p><b>为什么需要它</b>：memory-service 的 enrichment 流程调用 ai-service 4 个端点
 * （extract / reconstruct / indexVector / deleteVector），每个都走 Feign 直连或
 * 间接经过 MQ 触发。ai-service 内部挂了 LLM / Milvus / Neo4j 任何一个上游时，
 * 慢响应会沿着 Feign 把 memory-service 的 @Async 线程池耗光，最终体现在
 * "管理员看到 admin stats 正常但用户场景重建卡死"。
 *
 * <p><b>防护策略</b>（每方法独立配置，可调）：
 * <ul>
 *   <li>{@code @CircuitBreaker(name = "ai-service", fallbackMethod = "...")} —— 50% 失败率
 *       持续 10s 后熔断 30s，期间所有调用走 fallback 不再打 ai-service</li>
 *   <li>{@code @Retry} —— 瞬时故障重试 2 次（指数退避 100ms/200ms），只对非 5xx 重试
 *       （避免对"业务不存在"等终态错误重试）</li>
 * </ul>
 *
 * <p><b>调用约定</b>：fallback 方法签名 = 原始方法 + 末尾 {@code Throwable} 参数。
 * 触发 fallback 的两类场景：① 熔断器 OPEN ② 重试用尽仍失败。
 *
 * <p><b>性能开销</b>：resilience4j 的 AOP 织入是单方法级 in-memory state map，
 * 不引入 RPC 调用的额外一跳；实测额外延迟 < 0.1ms。
 */
@Component
public class AiServiceGateway {

    private static final Logger log = LoggerFactory.getLogger(AiServiceGateway.class);
    public static final String CB_NAME = "ai-service";

    private final AiServiceClient feign;

    public AiServiceGateway(AiServiceClient feign) {
        this.feign = feign;
    }

    // ====================================================================
    // 1. extractEntities —— 实体提取，驱动 Neo4j 图谱写入
    // ====================================================================

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "extractEntitiesFallback")
    @Retry(name = CB_NAME)
    public ApiResponse<EntityExtractionResponse> extractEntities(Map<String, Object> payload) {
        return feign.extractEntities(payload);
    }

    @SuppressWarnings("unused")
    private ApiResponse<EntityExtractionResponse> extractEntitiesFallback(Map<String, Object> payload, Throwable t) {
        log.warn("[ai-gw] extractEntities → circuit-open/retries-exhausted reason={} payloadKeys={}",
                t.toString(), payload == null ? 0 : payload.size());
        return ApiResponse.error(503, "ai-service 暂不可用，实体提取已跳过（不影响主流程）");
    }

    // ====================================================================
    // 2. reconstruct —— 场景重建（用户最关心的"进 SceneViewer 看 3D"）
    // ====================================================================

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "reconstructFallback")
    @Retry(name = CB_NAME)
    public Map<String, Object> reconstruct(Map<String, Object> payload) {
        return feign.reconstruct(payload);
    }

    @SuppressWarnings("unused")
    private Map<String, Object> reconstructFallback(Map<String, Object> payload, Throwable t) {
        log.warn("[ai-gw] reconstruct → circuit-open/retries-exhausted reason={}", t.toString());
        return null;  // 兼容 caller：null 走"无 AI 重建"分支（enrichWithReconstruction 已支持）
    }

    // ====================================================================
    // 3. indexVector —— 向量 upsert 到 Milvus
    // ====================================================================

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "indexVectorFallback")
    @Retry(name = CB_NAME)
    public ApiResponse<Map<String, Object>> indexVector(Map<String, Object> payload) {
        return feign.indexVector(payload);
    }

    @SuppressWarnings("unused")
    private ApiResponse<Map<String, Object>> indexVectorFallback(Map<String, Object> payload, Throwable t) {
        log.warn("[ai-gw] indexVector → circuit-open/retries-exhausted memoryId={} reason={}",
                payload == null ? null : payload.get("memoryId"), t.toString());
        return ApiResponse.error(503, "ai-service 暂不可用，向量索引进 outbox 兜底");
    }

    // ====================================================================
    // 4. deleteVector —— 记忆删除时清理向量
    // ====================================================================

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "deleteVectorFallback")
    @Retry(name = CB_NAME)
    public ApiResponse<Map<String, Object>> deleteVector(String memoryId) {
        return feign.deleteVector(memoryId);
    }

    @SuppressWarnings("unused")
    private ApiResponse<Map<String, Object>> deleteVectorFallback(String memoryId, Throwable t) {
        log.warn("[ai-gw] deleteVector → circuit-open/retries-exhausted memoryId={} reason={}",
                memoryId, t.toString());
        return ApiResponse.error(503, "ai-service 暂不可用，向量删除待补");
    }
}
