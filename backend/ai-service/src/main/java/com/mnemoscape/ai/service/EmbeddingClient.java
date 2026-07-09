package com.mnemoscape.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.config.VectorStoreProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 文本 → 稠密向量的 Embedding 客户端。
 *
 * <p>走 NVIDIA Integrate 的 OpenAI 兼容 {@code /v1/embeddings} 端点，用 JDK
 * {@link HttpClient} 直连 —— 与 {@link VisionDescriber} 同样的"裸 HttpClient"
 * 风格，避开 Spring AI EmbeddingModel 在 1.0.0-M4 对 NVIDIA 扩展字段
 * （{@code input_type}）支持不全的问题，也对 application/octet-stream 等
 * 非典型响应更鲁棒。
 *
 * <p><b>NVIDIA 检索 embedding 的非对称约定</b>：检索专用模型（如
 * {@code nvidia/nv-embedqa-e5-v5}）要求区分 {@code input_type}：
 * <ul>
 *   <li>{@code passage} — 索引时（把记忆正文存进 Milvus）；</li>
 *   <li>{@code query}   — 检索时（把用户问题转成查询向量）。</li>
 * </ul>
 * 用错类型会让召回质量明显下降，所以这里暴露 {@link #embedPassage} /
 * {@link #embedQuery} 两个语义化方法。
 *
 * <p><b>降级</b>：缺 key / 上游失败时抛 {@link AiUpstreamException}，由调用方
 * （VectorIndexService / MilvusSearchTool）捕获后退回关键词检索，绝不阻塞主流程。
 */
@Service
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    private final AiUpstreamProperties aiProps;
    private final VectorStoreProperties vecProps;
    private final String configuredApiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();
    /**
     * 可选依赖：用于把"最近一次真实 embed 的维度"回写给
     * {@link VectorIndexService#recordObservedDimension(int)}，让
     * {@code VectorIndexService.isReady()} 能做"实际维度 vs 配置维度"对齐校验。
     *
     * <p><b>为何用 {@link ObjectProvider} 而非直接注入</b>：
     * {@code VectorIndexService} 的构造器反向依赖 {@code EmbeddingClient}，
     * 而 {@code MilvusSearchTool}（@Configuration）又被 Spring AI 的
     * {@code OpenAiAutoConfiguration#openAiChatModel} 通过 {@code List<FunctionCallback>}
     * 牵进 ChatModel 装配链，最终在 Spring Boot 3.x 严格模式
     * （{@code spring.main.allow-circular-references=false}）下形成不可解的环。
     * <p>{@code ObjectProvider} 是惰性 lookup —— 构造期只拿到 provider 句柄，
     * 不解析真正的 bean，因此彻底打破环。{@code embed()} 真正运行时再用
     * {@link ObjectProvider#getIfAvailable()} 取 bean；缺 bean / 单测环境
     * 返回 null，回调直接跳过，绝不阻塞主流程。
     */
    private final ObjectProvider<VectorIndexService> vectorIndexServiceProvider;
    /**
     * 性能加速层（C 系列改造）：
     * <ul>
     *   <li>缓存命中 → 跳过 NVIDIA HTTP 调用，毫秒级返回</li>
     *   <li>未命中 → 先过限流，再调 NVIDIA，成功后回写缓存</li>
     * </ul>
     * 同样用 {@link ObjectProvider}：单测 / Redis 不可用时静默走"无缓存无限流"老路径。
     */
    private final ObjectProvider<AiCacheService> aiCacheProvider;

    public EmbeddingClient(AiUpstreamProperties aiProps,
                           VectorStoreProperties vecProps,
                           org.springframework.core.env.Environment env,
                           ObjectProvider<VectorIndexService> vectorIndexServiceProvider,
                           ObjectProvider<AiCacheService> aiCacheProvider,
                           @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}")
                           String baseUrl) {
        this.aiProps = aiProps;
        this.vecProps = vecProps;
        this.configuredApiKey = env.getProperty("spring.ai.openai.api-key", "");
        this.vectorIndexServiceProvider = vectorIndexServiceProvider;
        this.aiCacheProvider = aiCacheProvider;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 索引侧：把一段记忆正文编码成向量。 */
    public float[] embedPassage(String text) {
        return embed(text, "passage");
    }

    /** 检索侧：把用户查询编码成向量。 */
    public float[] embedQuery(String text) {
        return embed(text, "query");
    }

    /** key 是否就绪（占位符 / 空 → 不可用）。 */
    public boolean isConfigured() {
        return configuredApiKey != null
                && !configuredApiKey.isBlank()
                && !configuredApiKey.startsWith(aiProps.getPlaceholderKeyPrefix());
    }

    /**
     * 真正的 embedding 调用。
     *
     * @param text      待编码文本（会被裁剪到合理上限，避免超 token）
     * @param inputType {@code query} 或 {@code passage}
     * @return 稠密向量；维度应等于 {@link VectorStoreProperties#getEmbeddingDimension()}
     * @throws AiUpstreamException 缺 key / 上游 4xx-5xx / 超时 / 解析失败
     */
    @CircuitBreaker(name = "deepseek", fallbackMethod = "embedFallback")
    public float[] embed(String text, String inputType) {
        ensureRealKeyOrThrow();
        if (text == null || text.isBlank()) {
            throw new AiUpstreamException(AiUpstreamException.Reason.UNKNOWN,
                    "Cannot embed empty text");
        }
        String clipped = text.length() > 4000 ? text.substring(0, 4000) : text;

        // ---------- C-1: Redis embedding 缓存 (model, type, text) → vector ----------
        // 命中即返回，跳过 NVIDIA 调用 (~200ms → <1ms) 并节省 Token 配额。
        AiCacheService cache = aiCacheProvider.getIfAvailable();
        if (cache != null) {
            float[] hit = cache.getCachedEmbedding(vecProps.getEmbeddingModel(), inputType, clipped);
            if (hit != null) {
                log.debug("[EmbeddingClient] cache HIT model={} type={} dim={}",
                        vecProps.getEmbeddingModel(), inputType, hit.length);
                return hit;
            }
            // ---------- C-3: 本地令牌桶限流，避免 NVIDIA 429 ----------
            cache.acquireOrThrow(AiCacheService.BUCKET_EMBED);
        }

        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", vecProps.getEmbeddingModel());
            ArrayNode input = body.putArray("input");
            input.add(clipped);
            // NVIDIA 检索 embedding 的非对称扩展字段；OpenAI 原生模型会忽略它。
            body.put("input_type", inputType);
            // 截断策略：根据 nv-embed-v1 模型规格调整为 NONE
            body.put("truncate", "NONE");
            body.put("encoding_format", "float");

            String requestBody = json.writeValueAsString(body);
            long t0 = System.currentTimeMillis();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(resolveEmbeddingUrl()))
                    .timeout(Duration.ofMillis(Math.max(vecProps.getEmbeddingTimeoutMs(), 5_000)))
                    .header("Authorization", "Bearer " + configuredApiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            String responseJson = resp.body() == null ? "" : new String(resp.body(), StandardCharsets.UTF_8);

            if (status >= 400) {
                String snippet = responseJson.length() > 320 ? responseJson.substring(0, 320) + "..." : responseJson;
                throw new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                        "Embedding upstream error: HTTP " + status + " " + snippet);
            }

            float[] vec = extractVector(responseJson);
            log.info("[EmbeddingClient] model={} type={} dim={} elapsedMs={}",
                    vecProps.getEmbeddingModel(), inputType, vec.length, System.currentTimeMillis() - t0);
            // 把"实际拿到的维度"回写给 VectorIndexService（如果 bean 存在）。
            // ObjectProvider.getIfAvailable() 在缺 bean / 单测环境返回 null，静默跳过。
            VectorIndexService vis = vectorIndexServiceProvider.getIfAvailable();
            if (vis != null) {
                try {
                    vis.recordObservedDimension(vec.length);
                } catch (Exception ignored) {
                    // dimension observation is best-effort; never break embedding on observation failure.
                }
            }
            // 回写缓存（C-1）。失败静默，不影响本次返回。
            if (cache != null) {
                cache.cacheEmbedding(vecProps.getEmbeddingModel(), inputType, clipped, vec);
            }
            return vec;
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e);
        }
    }

    private float[] embedFallback(String text, String inputType, Throwable t) {
        log.warn("[circuit-breaker] embed fallback: {}", t.toString());
        return null;
    }

    /** 解析 OpenAI 兼容 embeddings 响应：{@code data[0].embedding}。 */
    private float[] extractVector(String responseJson) {
        try {
            JsonNode node = json.readTree(responseJson);
            JsonNode data = node.path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode emb = data.get(0).path("embedding");
                if (emb.isArray() && emb.size() > 0) {
                    float[] out = new float[emb.size()];
                    for (int i = 0; i < emb.size(); i++) {
                        out[i] = (float) emb.get(i).asDouble();
                    }
                    return out;
                }
            }
            throw new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                    "Embedding response missing data[].embedding");
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                    "Failed to parse embedding response: " + e.getMessage(), e);
        }
    }

    private String resolveEmbeddingUrl() {
        String base = vecProps.getEmbeddingBaseUrl();
        if (base == null || base.isBlank()) {
            base = this.baseUrl;
        }
        base = base.replaceAll("/+$", "");
        if (base.contains("ai.api.nvidia.com")) {
            return base + "/embed";
        } else if (base.contains("integrate.api.nvidia.com")) {
            return base + "/v1/embeddings";
        } else {
            if (base.endsWith("/embeddings") || base.endsWith("/embed")) {
                return base;
            }
            return base + "/v1/embeddings";
        }
    }

    private void ensureRealKeyOrThrow() {
        if (!isConfigured()) {
            throw new AiUpstreamException(AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY is not configured; embedding cannot run.");
        }
    }

    private AiUpstreamException classify(Throwable t) {
        String msg = t == null ? "" : String.valueOf(t.getMessage());
        String low = msg == null ? "" : msg.toLowerCase(java.util.Locale.ROOT);
        AiUpstreamException.Reason reason;
        if (low.contains("401") || low.contains("403") || low.contains("unauthor")) {
            reason = AiUpstreamException.Reason.AUTHENTICATION;
        } else if (low.contains("timeout") || low.contains("timed out")) {
            reason = AiUpstreamException.Reason.TIMEOUT;
        } else {
            reason = AiUpstreamException.Reason.UPSTREAM_ERROR;
        }
        return new AiUpstreamException(reason, "Embedding 调用失败 (" + msg + ")", t);
    }
}
