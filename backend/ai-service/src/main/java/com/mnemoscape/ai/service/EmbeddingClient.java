package com.mnemoscape.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.config.VectorStoreProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public EmbeddingClient(AiUpstreamProperties aiProps,
                           VectorStoreProperties vecProps,
                           org.springframework.core.env.Environment env,
                           @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}")
                           String baseUrl) {
        this.aiProps = aiProps;
        this.vecProps = vecProps;
        this.configuredApiKey = env.getProperty("spring.ai.openai.api-key", "");
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
    public float[] embed(String text, String inputType) {
        ensureRealKeyOrThrow();
        if (text == null || text.isBlank()) {
            throw new AiUpstreamException(AiUpstreamException.Reason.UNKNOWN,
                    "Cannot embed empty text");
        }
        String clipped = text.length() > 4000 ? text.substring(0, 4000) : text;

        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", vecProps.getEmbeddingModel());
            ArrayNode input = body.putArray("input");
            input.add(clipped);
            // NVIDIA 检索 embedding 的非对称扩展字段；OpenAI 原生模型会忽略它。
            body.put("input_type", inputType);
            // 截断策略：交给上游，避免超长直接 422。
            body.put("truncate", "END");
            body.put("encoding_format", "float");

            String requestBody = json.writeValueAsString(body);
            long t0 = System.currentTimeMillis();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + EMBEDDINGS_PATH))
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
                throw new RuntimeException("Embedding upstream error: HTTP " + status + " " + snippet);
            }

            float[] vec = extractVector(responseJson);
            log.info("[EmbeddingClient] model={} type={} dim={} elapsedMs={}",
                    vecProps.getEmbeddingModel(), inputType, vec.length, System.currentTimeMillis() - t0);
            return vec;
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e);
        }
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
            throw new RuntimeException("Embedding response missing data[].embedding");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse embedding response: " + e.getMessage(), e);
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
