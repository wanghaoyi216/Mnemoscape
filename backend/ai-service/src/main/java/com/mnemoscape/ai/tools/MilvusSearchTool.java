package com.mnemoscape.ai.tools;

import com.mnemoscape.ai.client.MemoryServiceClient;
import com.mnemoscape.ai.tools.audit.Tool;
import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 向量检索工具（真实稠密向量 + 关键词降级双轨）。
 *
 * <p><b>检索路径（{@link #searchForUser}）</b>：
 * <ol>
 *   <li><b>真实向量检索</b>：用 {@link com.mnemoscape.ai.service.EmbeddingClient}
 *       把 query 编码成向量，再用 {@link com.mnemoscape.ai.service.MilvusVectorStore}
 *       做 COSINE 相似度召回（按 {@code user_id} 过滤，多租户隔离）。</li>
 *   <li><b>关键词降级</b>：Embedding / Milvus 任一不可用或失败时，自动退回
 *       "基于 memory-service 真实数据 + 关键词加权打分"的老实现 —— 检索能力
 *       不中断，只是召回精度退化。</li>
 * </ol>
 *
 * <p>两条路径都：
 * <ul>
 *   <li>从 SecurityContext（或显式 userId 入参）拿身份，
 *       <b>绝不接受用户提示词里的 userId 覆盖</b>，避免越权。</li>
 *   <li>返回 topK 条命中 + 相似度，对外契约一致。</li>
 * </ul>
 */
@Configuration
public class MilvusSearchTool {

    private static final Logger log = LoggerFactory.getLogger(MilvusSearchTool.class);

    public static class Request {
        /** 自然语言查询；模型会自己改写关键词 */
        public String query;
        /** 取前 K 条；默认 5，最多 20 */
        public Integer topK;
    }

    public static class Hit {
        public String memoryId;
        public String title;
        public String location;
        public Integer year;
        public String snippet;
        public double score;

        public Hit() {}
        public Hit(String id, String title, String location, Integer year, String snippet, double score) {
            this.memoryId = id; this.title = title; this.location = location;
            this.year = year; this.snippet = snippet; this.score = score;
        }
    }

    public static class Response {
        public List<Hit> hits = new ArrayList<>();
        public boolean degraded = false;
        public String message;
    }

    private final MemoryServiceClient memoryClient;
    /** 真实向量检索依赖；为空（纯单测 / 未装配）时自动退回关键词检索。 */
    private final com.mnemoscape.ai.service.EmbeddingClient embeddingClient;
    private final com.mnemoscape.ai.service.MilvusVectorStore vectorStore;
    /** 搜索结果缓存层 (C-2)；为空走"无缓存直查 Milvus"。 */
    private final com.mnemoscape.ai.service.AiCacheService aiCache;

    public MilvusSearchTool(MemoryServiceClient memoryClient,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            com.mnemoscape.ai.service.EmbeddingClient embeddingClient,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            com.mnemoscape.ai.service.MilvusVectorStore vectorStore,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            com.mnemoscape.ai.service.AiCacheService aiCache) {
        this.memoryClient = memoryClient;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.aiCache = aiCache;
    }

    /** 暴露为 FunctionCallback bean，由 ChatClient 通过 OpenAI tool-calling 调用。
     *  注意：@Bean 方法名不能与所在 @Configuration 类的 bean 名相同，否则
     *  Spring 会因为同名 bean 拒绝启动。模型可见的 tool 名仍是
     *  {@code milvusSearchTool}（由 {@code .function("milvusSearchTool", fn)} 设定）。 */
    @Bean
    public FunctionCallback milvusSearchToolCallback() {
        Function<Request, Response> fn = this::search;
        return FunctionCallback.builder()
                .description("Search the current user's authorized memories by keyword; "
                        + "returns up to topK matches with title, location, year, snippet and score. "
                        + "Use when the user asks to find / search / recall memories or "
                        + "asks 'do I have memories about X'.")
                .function("milvusSearchTool", fn)
                .inputType(Request.class)
                .build();
    }

    /** 真实查询：先从 memory-service 拉用户记忆，再按关键词打分。 */
    @SuppressWarnings("unchecked")
    @Tool("milvusSearchTool")
    public Response search(Request req) {
        return searchForUser(req, currentUserId());
    }

    /**
     * 同 {@link #search(Request)}，但显式接受 userId 参数 — 给 ChatReasoner 的
     * "强制 RAG"路径用：那条路径走 servlet 而不是 SecurityContext，没法通过
     * {@link #currentUserId()} 拿到身份。
     *
     * <p>调用方负责保证 userId 来自网关 X-User-Id 而非用户输入。
     */
    @SuppressWarnings("unchecked")
    public Response searchForUser(Request req, String userId) {
        Response resp = new Response();
        if (req == null) {
            resp.degraded = true;
            resp.message = "Empty request";
            return resp;
        }
        if (userId == null) {
            resp.degraded = true;
            resp.message = "No authenticated user";
            return resp;
        }
        int topK = req.topK == null ? 5 : Math.max(1, Math.min(20, req.topK));

        // 1) 优先尝试真实稠密向量检索（Embedding + Milvus）。
        //    任一环节不可用 / 抛错 / 返回 null → 透明退回关键词加权检索。
        Response vectorResp = tryVectorSearch(req, userId, topK);
        if (vectorResp != null) {
            return vectorResp;
        }

        // 2) 关键词加权降级（老实现）。
        return keywordSearch(req, userId, topK);
    }

    /**
     * 真实向量检索路径。返回 null 表示"不可用 / 失败"，调用方应降级到关键词检索；
     * 返回非 null（哪怕 hits 为空）表示向量检索成功执行（空 = 真没命中）。
     */
    private Response tryVectorSearch(Request req, String userId, int topK) {
        if (embeddingClient == null || vectorStore == null) return null;
        if (!vectorStore.isEnabled() || !embeddingClient.isConfigured()) return null;
        if (req.query == null || req.query.isBlank()) return null;

        // ---------- C-2: 60s 结果缓存 ----------
        // 同一用户同一 query 在 60 秒内复用结果。写入失效由 memory-service 通过
        // RabbitMQ "memory.indexed" 事件触发 aiCache.invalidateUserSearch(userId) 主动清理 (A 系列).
        if (aiCache != null) {
            List<Hit> cached = aiCache.getCachedSearch(userId, topK, req.query);
            if (cached != null) {
                Response resp = new Response();
                resp.hits = new ArrayList<>(cached);
                resp.message = "vector-cache";
                log.debug("[milvusSearchTool] cache HIT userId={} topK={} hits={}",
                        userId, topK, cached.size());
                return resp;
            }
        }

        try {
            float[] qv = embeddingClient.embedQuery(req.query);
            List<com.mnemoscape.ai.service.MilvusVectorStore.SearchHit> hits =
                    vectorStore.search(qv, userId, topK);
            if (hits == null) return null; // Milvus 不可用 → 降级
            Response resp = new Response();
            for (com.mnemoscape.ai.service.MilvusVectorStore.SearchHit h : hits) {
                resp.hits.add(new Hit(h.memoryId, h.title, h.location, h.year, h.snippet, h.score));
            }
            resp.message = "vector";
            log.info("[milvusSearchTool] vector search returned {} hits (userId={})",
                    resp.hits.size(), userId);
            // 写入缓存。失败静默。
            if (aiCache != null) {
                aiCache.cacheSearch(userId, topK, req.query, resp.hits);
            }
            return resp;
        } catch (Exception e) {
            log.warn("[milvusSearchTool] vector search failed, falling back to keyword: {}", e.toString());
            return null;
        }
    }

    /** 关键词加权检索：从 memory-service 拉用户记忆后按关键词命中打分。 */
    @SuppressWarnings("unchecked")
    private Response keywordSearch(Request req, String userId, int topK) {
        Response resp = new Response();
        List<String> kws = extractKeywords(req.query);

        try {
            ApiResponse<Map<String, Object>> raw = memoryClient.listMemories(0, 50, userId);
            if (raw == null || raw.getData() == null) {
                resp.degraded = true;
                resp.message = "memory-service returned empty";
                return resp;
            }
            Object items = raw.getData().get("items");
            if (!(items instanceof List<?> list)) return resp;

            List<Hit> all = new ArrayList<>();
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) continue;
                String id = str(m.get("id"));
                String title = str(m.get("title"));
                String location = str(m.get("memoryLocation"));
                Integer year = m.get("memoryYear") instanceof Number n ? n.intValue() : null;
                String desc = str(m.get("description"));
                String hay = (title + " " + location + " " + desc).toLowerCase(Locale.ROOT);
                int score = 0;
                for (String k : kws) if (hay.contains(k.toLowerCase(Locale.ROOT))) score++;
                if (score > 0 || kws.isEmpty()) {
                    double sim = Math.min(0.99, 0.55 + 0.08 * score);
                    String snippet = desc == null ? "" : (desc.length() > 160 ? desc.substring(0, 160) + "…" : desc);
                    all.add(new Hit(id, title, location, year, snippet, sim));
                }
            }
            all.sort((a, b) -> Double.compare(b.score, a.score));
            int limit = Math.min(topK, all.size());
            resp.hits = all.subList(0, limit);
            return resp;
        } catch (Exception e) {
            log.warn("milvusSearchTool fallback (memory-service unreachable): {}", e.toString());
            resp.degraded = true;
            resp.message = "memory-service unreachable: " + e.getClass().getSimpleName();
            return resp;
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private List<String> extractKeywords(String query) {
        List<String> out = new ArrayList<>();
        if (query == null || query.isBlank()) return out;
        java.util.regex.Matcher cn = java.util.regex.Pattern.compile("[一-龥]{2,6}").matcher(query);
        while (cn.find()) {
            String s = cn.group();
            if (!out.contains(s)) out.add(s);
            if (out.size() >= 6) break;
        }
        java.util.regex.Matcher en = java.util.regex.Pattern.compile("[A-Za-z]{3,}").matcher(query);
        while (en.find()) {
            String s = en.group().toLowerCase(Locale.ROOT);
            if (!out.contains(s)) out.add(s);
            if (out.size() >= 8) break;
        }
        return out;
    }

    /**
     * 拿当前 SecurityContext 里的 userId。
     *
     * <p>{@code JwtAuthFilter} 把 userId 当作 principal 注入；这里只信任它，
     * 不接受工具入参伪造的 userId。
     */
    public static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        return principal == null ? null : principal.toString();
    }

    /** 给单元测试用：避免直接构造 Map。 */
    public static Map<String, Object> hitToMap(Hit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("memoryId", h.memoryId);
        m.put("title", h.title);
        m.put("location", h.location);
        m.put("year", h.year);
        m.put("snippet", h.snippet);
        m.put("score", h.score);
        return m;
    }
}
