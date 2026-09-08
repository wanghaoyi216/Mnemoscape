package com.mnemoscape.ai.service;

import com.mnemoscape.ai.config.VectorStoreProperties;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**

 * 记忆向量索引编排层 —— 把 {@link EmbeddingClient}（文本→向量）和
 * {@link MilvusVectorStore}（向量库读写）串起来，供 controller / 工具调用。
 *
 * <p><b>全程 best-effort</b>：embedding 或 Milvus 任一不可用时，index / delete
 * 返回 false（记日志），<b>绝不</b>抛异常打断记忆创建 / 删除主流程。检索侧的
 * 降级在 {@link com.mnemoscape.ai.tools.MilvusSearchTool} 里：vector 检索拿不到
 * 结果时退回关键词加权。
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    private final VectorStoreProperties props;
    private final EmbeddingClient embeddingClient;
    private final MilvusVectorStore vectorStore;
    private final ChatClient chatClient;
    private final AiUpstreamProperties aiUpstreamProps;
    private final String configuredApiKey;

    public VectorIndexService(VectorStoreProperties props,
                              EmbeddingClient embeddingClient,
                              MilvusVectorStore vectorStore,
                              @Qualifier("mnemoscapeChatClientBuilder") ChatClient.Builder builder,
                              AiUpstreamProperties aiUpstreamProps,
                              @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.props = props;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.chatClient = builder.build();
        this.aiUpstreamProps = aiUpstreamProps;
        this.configuredApiKey = apiKey;
    }


    /**
     * 最近一次真实 embedding 调用返回的向量维度。{@code -1} = 还没观察到任何向量。
     * 由 {@link EmbeddingClient}（在 {@code embedQuery} / {@code embedPassage} 返回时）
     * 写入；{@link #isReady()} 拿它跟 {@link VectorStoreProperties#getEmbeddingDimension()}
     * 对齐校验 —— 防止误配 1024 维模型跑 4096 维 Milvus collection 的"插入 / 检索维度
     * 不一致"灾难（这种问题 Milvus 不会自动检查，第一次 upsert 才会 422）。
     */
    private volatile int lastObservedDimension = -1;

    /** 给 {@link EmbeddingClient} 回调用：记录一次真实 embed 的维度。 */
    public void recordObservedDimension(int dim) {
        this.lastObservedDimension = dim;
    }

    /** 给 {@link com.mnemoscape.ai.controller.VectorAdminController} 用的只读访问。 */
    public int getLastObservedDimension() {
        return lastObservedDimension;
    }

    public boolean isReady() {
        if (!(props.isEnabled() && embeddingClient.isConfigured() && vectorStore.isEnabled())) {
            return false;
        }
        int expected = props.getEmbeddingDimension();
        int observed = lastObservedDimension;
        if (observed < 0) {
            // 还没观察过任何向量（启动后没真实 embed 过）—— 视为"维度未知"，
            // 仍然按"就绪"放行（搜索路径里能拿到再校验）。
            return true;
        }
        if (observed != expected) {
            log.warn("[VectorIndex] isReady=false: dimension mismatch observed={} expected={}. " +
                    "Check mnemoscape.ai.vector.embedding-model vs embedding-dimension.",
                    observed, expected);
            return false;
        }
        return true;
    }

    /**
     * 索引（upsert）一条记忆。
     *
     * @return true 仅当 embedding + Milvus upsert 都成功
     */
    public boolean index(String memoryId, String userId, String title,
                         String location, Integer year, String description) {
        return index(memoryId, userId, title, location, year, description, null);
    }

    /**
     * 索引（upsert）一条记忆（带隐私级别 — 共鸣大厅公共检索需要它过滤）。
     *
     * @return true 仅当 embedding + Milvus upsert 都成功
     */
    public boolean index(String memoryId, String userId, String title,
                         String location, Integer year, String description, String privacy) {
        if (!isReady()) {
            log.debug("[VectorIndex] skipped (not ready) memory={}", memoryId);
            return false;
        }
        if (memoryId == null || memoryId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        String composite = buildEmbeddingText(title, location, year, description);
        if (composite.isBlank()) return false;

        try {
            float[] vector = embeddingClient.embedPassage(composite);
            MilvusVectorStore.VectorRecord rec = new MilvusVectorStore.VectorRecord();
            rec.memoryId = memoryId;
            rec.userId = userId;
            rec.title = title;
            rec.location = location;
            rec.year = year;
            rec.snippet = generatePrivacySafeSummary(description);
            rec.privacy = normalizePrivacy(privacy);
            rec.vector = vector;
            boolean ok = vectorStore.upsert(rec);
            log.info("[VectorIndex] index memory={} user={} privacy={} ok={}",
                    memoryId, userId, rec.privacy, ok);
            return ok;
        } catch (Exception e) {
            log.warn("[VectorIndex] index failed for memory {}: {}", memoryId, e.toString());
            return false;
        }
    }

    /**
     * 共鸣大厅：用一段 seed 文本做跨用户公共向量检索。
     *
     * @return 命中列表（已带 score）；不可用 / 失败返回 null（调用方降级到关键词打分）
     */
    public List<MilvusVectorStore.SearchHit> searchPublic(String seedText, String excludeUserId, int topK) {
        if (!isReady() || seedText == null || seedText.isBlank()) return null;
        try {
            float[] qv = embeddingClient.embedQuery(seedText);
            return vectorStore.searchPublic(qv, excludeUserId, topK);
        } catch (Exception e) {
            log.warn("[VectorIndex] public search failed: {}", e.toString());
            return null;
        }
    }

    private static String normalizePrivacy(String privacy) {
        if (privacy == null || privacy.isBlank()) return "PRIVATE";
        String p = privacy.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (p) {
            case "PUBLIC", "FRIENDS", "PRIVATE" -> p;
            default -> "PRIVATE";
        };
    }

    /** 删除一条记忆向量。best-effort。 */
    public boolean delete(String memoryId) {
        if (!props.isEnabled() || !vectorStore.isEnabled()) return false;
        try {
            return vectorStore.deleteById(memoryId);
        } catch (Exception e) {
            log.warn("[VectorIndex] delete failed for memory {}: {}", memoryId, e.toString());
            return false;
        }
    }

    /** 把结构化字段拼成一段适合 embedding 的复合文本。 */
    static String buildEmbeddingText(String title, String location, Integer year, String description) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) sb.append(title).append("。");
        if (location != null && !location.isBlank()) sb.append("地点：").append(location).append("。");
        if (year != null && year > 0) sb.append("年份：").append(year).append("。");
        if (description != null && !description.isBlank()) sb.append(description);
        return sb.toString().trim();
    }

    private String generatePrivacySafeSummary(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        if (chatClient == null) {
            return snippet(description);
        }
        try {
            if (configuredApiKey == null
                    || configuredApiKey.isBlank()
                    || configuredApiKey.startsWith(aiUpstreamProps.getPlaceholderKeyPrefix())) {
                return snippet(description);
            }

            String prompt = """
                你是 Mnemoscape 的记忆脱敏与摘要助手。请将以下用户的记忆描述转换成一段简短的、**不含任何个人隐私**的概述。
                
                脱敏要求：
                1. 绝对不能出现任何具体的人名（如“张三”、“小明”等，请用“一位朋友”、“家人”等泛指代替）。
                2. 绝对不能出现具体的电话、账号、详细地址/学校名/公司名（如“南京路100号”、“清华大学”，请用“街道上”、“学校里”、“公司中”等宽泛地理词汇代替）。
                3. 保留记忆的核心情感基调与大致画面感（例如：在夏夜的星空下和朋友聊天，或者秋天落叶时在公园散步）。
                4. 字数控制在 100 字以内，语言优美、充满意境。
                5. 仅输出生成的概述文字本身，不要有任何修饰性前缀、标点或 markdown 语法。
                
                记忆描述：
                ---
                %s
                ---
                
                现在输出脱敏概述：
                """.formatted(description);

            String summary = chatClient.prompt()
                    .user(prompt)
                    .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                            .withModel(aiUpstreamProps.getAgenticModel())
                            .build())
                    .call()
                    .content();

            if (summary == null || summary.isBlank()) {
                return snippet(description);
            }
            return summary.trim();
        } catch (Exception e) {
            log.warn("[VectorIndex] Failed to generate privacy-safe summary via LLM: {}. Falling back to default snippet.", e.toString());
            return snippet(description);
        }
    }

    private static String snippet(String description) {
        if (description == null) return "";
        String d = description.strip();
        return d.length() > 160 ? d.substring(0, 160) + "…" : d;
    }
}

