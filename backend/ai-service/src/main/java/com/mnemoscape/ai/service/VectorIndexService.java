package com.mnemoscape.ai.service;

import com.mnemoscape.ai.config.VectorStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public VectorIndexService(VectorStoreProperties props,
                              EmbeddingClient embeddingClient,
                              MilvusVectorStore vectorStore) {
        this.props = props;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    public boolean isReady() {
        return props.isEnabled() && embeddingClient.isConfigured() && vectorStore.isEnabled();
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
            rec.snippet = snippet(description);
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

    private static String snippet(String description) {
        if (description == null) return "";
        String d = description.strip();
        return d.length() > 160 ? d.substring(0, 160) + "…" : d;
    }
}
