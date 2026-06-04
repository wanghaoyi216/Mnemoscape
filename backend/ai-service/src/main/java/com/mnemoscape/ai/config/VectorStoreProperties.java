package com.mnemoscape.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 真实向量检索（Embedding + Milvus）配置。
 *
 * <p>通过 {@code @ConfigurationProperties("mnemoscape.ai.vector")} 绑定。
 *
 * <p><b>设计原则 — 永远可降级</b>：本平台的中间件（Milvus 在 Tailscale
 * {@code 100.66.166.46:19530}）和上游 Embedding 模型（NVIDIA Integrate）都可能
 * 临时不可达。所以：
 * <ul>
 *   <li>{@link #enabled} = false（或 Milvus / Embedding 任一调用失败）时，
 *       {@code MilvusSearchTool} 自动退回"关键词 jaccard 加权"老实现 —— 用户感知
 *       的检索功能不中断，只是召回精度退化。</li>
 *   <li>所有写入（index / delete）都是 best-effort：失败只记日志，不阻塞记忆
 *       创建 / 删除主流程。</li>
 * </ul>
 *
 * <p><b>Embedding 模型</b>：默认 {@code nvidia/nv-embed-v1}（4096 维，
 * NVIDIA Integrate 上的通用 embedding，OpenAI 兼容 {@code /v1/embeddings}
 * 协议 + NVIDIA 扩展的 {@code input_type=query|passage}）。换模型时务必同步改
 * {@link #embeddingDimension}，否则 Milvus collection 维度对不上会插入失败。
 *
 * <p><b>Milvus 接入方式</b>：用 RESTful API v2（{@code /v2/vectordb/...}，与
 * gRPC 共用 19530 端口），通过 JDK {@code HttpClient} 调用 —— 避开 Milvus
 * gRPC SDK 与 Spring Cloud / Netty 的依赖冲突，与团队既有 {@code VisionDescriber}
 * 的"裸 HttpClient"风格一致。
 */
@Configuration
@ConfigurationProperties(prefix = "mnemoscape.ai.vector")
public class VectorStoreProperties {

    /** 总开关。false 时所有向量逻辑短路，MilvusSearchTool 走关键词降级。 */
    private boolean enabled = true;

    /* ---------------- Embedding ---------------- */

    /** NVIDIA Integrate 上的 embedding 模型 id。 */
    private String embeddingModel = "nvidia/nv-embed-v1";

    /** embedding 维度，必须与 {@link #embeddingModel} 实际输出一致 + Milvus collection 对齐。 */
    private int embeddingDimension = 4096;

    /** embedding 调用硬上限（毫秒）。 */
    private long embeddingTimeoutMs = 15_000;

    /* ---------------- Milvus REST ---------------- */

    /** Milvus 主机（默认 Tailscale 内网）。 */
    private String milvusHost = "100.66.166.46";

    /** Milvus 端口（gRPC + REST 复用 19530）。 */
    private int milvusPort = 19530;

    /** Milvus 鉴权 token（{@code username:password} 或 root token）。dev 单机一般为空。 */
    private String milvusToken = "";

    /** Milvus database 名（2.4+ 支持多 db；默认 {@code default}）。 */
    private String milvusDatabase = "default";

    /** 存放记忆向量的 collection 名。 */
    private String collectionName = "mnemoscape_memories";

    /** Milvus REST 单次调用硬上限（毫秒）。 */
    private long milvusTimeoutMs = 8_000;

    /** 检索默认返回条数（被请求里的 topK 覆盖；最终 clamp 到 [1,50]）。 */
    private int defaultTopK = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = Math.max(1, embeddingDimension);
    }

    public long getEmbeddingTimeoutMs() { return embeddingTimeoutMs; }
    public void setEmbeddingTimeoutMs(long embeddingTimeoutMs) { this.embeddingTimeoutMs = embeddingTimeoutMs; }

    public String getMilvusHost() { return milvusHost; }
    public void setMilvusHost(String milvusHost) { this.milvusHost = milvusHost; }

    public int getMilvusPort() { return milvusPort; }
    public void setMilvusPort(int milvusPort) { this.milvusPort = milvusPort; }

    public String getMilvusToken() { return milvusToken; }
    public void setMilvusToken(String milvusToken) { this.milvusToken = milvusToken; }

    public String getMilvusDatabase() { return milvusDatabase; }
    public void setMilvusDatabase(String milvusDatabase) { this.milvusDatabase = milvusDatabase; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public long getMilvusTimeoutMs() { return milvusTimeoutMs; }
    public void setMilvusTimeoutMs(long milvusTimeoutMs) { this.milvusTimeoutMs = milvusTimeoutMs; }

    public int getDefaultTopK() { return defaultTopK; }
    public void setDefaultTopK(int defaultTopK) { this.defaultTopK = Math.max(1, Math.min(defaultTopK, 50)); }

    /** Milvus REST base URL（无尾斜杠）。 */
    public String milvusBaseUrl() {
        return "http://" + milvusHost + ":" + milvusPort;
    }
}
