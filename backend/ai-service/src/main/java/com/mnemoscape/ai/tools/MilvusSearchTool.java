package com.mnemoscape.ai.tools;

import com.mnemoscape.ai.client.MemoryServiceClient;
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
 * 真实"向量检索"工具。
 *
 * <p>当前部署里 Milvus 仍是规划态（只有 .env 配置项，没有实际嵌入服务），
 * 因此这个工具的实现是"基于 memory-service 真实数据 + 关键词加权打分"的
 * RAG 替身：
 *
 * <ul>
 *   <li>从 SecurityContext 拿 userId（注入到 memory-service 的 X-User-Id），
 *       <b>绝不接受用户提示词里的 userId 覆盖</b>，避免越权。</li>
 *   <li>调用 memory-service {@code GET /memories} 拿到该用户授权的全部记忆。</li>
 *   <li>用 query 中提取的关键词与 title/location/description 做匹配打分，
 *       返回 topK 条 + 相似度近似值。</li>
 * </ul>
 *
 * <p>当真实 Milvus 上线时，仅替换实现内部逻辑（输入输出契约不变）。
 * 这种"真实数据 + 真实工具调用 + 待替换打分"的结构，比硬编码 Mock 要诚实得多：
 * 至少模型拿到的是用户的真实记忆而不是 stub。
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

    public MilvusSearchTool(MemoryServiceClient memoryClient) {
        this.memoryClient = memoryClient;
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
