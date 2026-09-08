package com.mnemoscape.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

/**
 * 控制 NVIDIA / 上游 LLM 接入的配置：
 *  - {@link #placeholderKeyPrefix} 命中 → 跳过真实调用，直接返回结构化降级；
 *  - {@link #timeoutMs}                上游单次请求硬上限；
 *  - {@link #fallbackMessage}          错误回放给前端的友好文案。
 *
 * 通过 {@code @ConfigurationProperties("mnemoscape.ai.upstream")} 绑定。
 *
 * <p>{@link RefreshScope} 让 Nacos config 推送时，bean 实例被销毁重建，
 * 所有引用此 Properties 的下游（{@code EmbeddingClient} / {@code VisionDescriber} /
 * {@code ChatReasoner}）都会拿到最新值 —— 切模型、调超时、改 fallback 文案
 * 都不用重启服务。
 */
@Configuration
@RefreshScope // 支持配置热更新
@ConfigurationProperties(prefix = "mnemoscape.ai.upstream")
public class AiUpstreamProperties {

    private String placeholderKeyPrefix = "nvapi-placeholder";
    private long timeoutMs = 30_000;
    private String fallbackMessage =
            "AI 暂时不可用。请确认服务端已注入 NVIDIA_API_KEY 环境变量后重试。";

    /**
     * 视觉模型 id（OpenAI 兼容 endpoint）。
     *
     * <p>选型说明（基于 NVIDIA Integrate /docs/multimodal-apis 页面验证）：
     * 默认走 {@code meta/llama-3.2-11b-vision-instruct} —— 同步 chat/completions
     * 端点，实测 ~5s 返回；同族 90B 模型在公网 trial 节点上经常出现
     * application/octet-stream 协议异常或 40~50s 超时，反而拖累 SSE 体验。
     * 若 11B 对场景描述精度不够，可通过 {@code NVIDIA_VISION_MODEL} 环境变量
     * 切换到 90B 或其他视觉模型。
     *
     * <p>Llama 3.2 Vision 在 image+text 场景只输出英文 —— 这反而契合本服务的
     * 「混合检索」架构：视觉模型只负责"看图→文字描述"前置，最终面对用户的是
     * 基座 M2.7（中文极强），它能把英文描述消化并用中文回复。
     */
    private String visionModel = "meta/llama-3.2-11b-vision-instruct";

    private String chatModel = "google/gemma-3n-e4b-it";
    private String reasoningModel = "bytedance/seed-oss-36b-instruct";
    private String agenticModel = "google/gemma-3n-e2b-it";
    private String generationModel = "meta/llama-4-maverick-17b-128e-instruct";

    /**
     * Embedding 模型（用于记忆向量化 + 相似度计算）。
     * 默认 nvidia/nv-embedqa-e5-v5 — 1024 维，支持中英文，余弦相似度。
     */
    private String embeddingModel = "nvidia/nv-embedqa-e5-v5";

    /**
     * 情感分析模型（用于 EmotionAnalysisTool）。
     * 默认用 chatModel 做 zero-shot 情感分类。
     */
    private String sentimentModel = "google/gemma-3n-e2b-it";

    /**
     * 视觉模型主选不可用时的降级（默认 90B Vision）。
     * 两者都不可用时，最终降级为"用文件名 + URL 注入 prompt"，让基座 M2.7 至少
     * 知道有附件，不要假装没看见。
     */
    private String visionFallbackModel = "meta/llama-3.2-90b-vision-instruct";

    /**
     * 视觉调用最多接受的图片数量（防滥用 + 防超长 prompt）。
     */
    private int visionMaxImages = 4;

    /**
     * 视觉模型单次调用的硬上限（毫秒）。Vision 模型相对较慢，给得更宽。
     */
    private long visionTimeoutMs = 45_000;

    public String getPlaceholderKeyPrefix() { return placeholderKeyPrefix; }
    public void setPlaceholderKeyPrefix(String placeholderKeyPrefix) {
        this.placeholderKeyPrefix = placeholderKeyPrefix;
    }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getFallbackMessage() { return fallbackMessage; }
    public void setFallbackMessage(String fallbackMessage) {
        this.fallbackMessage = fallbackMessage;
    }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public String getReasoningModel() { return reasoningModel; }
    public void setReasoningModel(String reasoningModel) { this.reasoningModel = reasoningModel; }
    public String getAgenticModel() { return agenticModel; }
    public void setAgenticModel(String agenticModel) { this.agenticModel = agenticModel; }
    public String getGenerationModel() { return generationModel; }
    public void setGenerationModel(String generationModel) { this.generationModel = generationModel; }

    public String getVisionFallbackModel() { return visionFallbackModel; }
    public void setVisionFallbackModel(String visionFallbackModel) {
        this.visionFallbackModel = visionFallbackModel;
    }
    public int getVisionMaxImages() { return visionMaxImages; }
    public void setVisionMaxImages(int visionMaxImages) {
        this.visionMaxImages = Math.max(1, Math.min(visionMaxImages, 16));
    }
    public long getVisionTimeoutMs() { return visionTimeoutMs; }
    public void setVisionTimeoutMs(long visionTimeoutMs) { this.visionTimeoutMs = visionTimeoutMs; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getSentimentModel() { return sentimentModel; }
    public void setSentimentModel(String sentimentModel) { this.sentimentModel = sentimentModel; }
}
