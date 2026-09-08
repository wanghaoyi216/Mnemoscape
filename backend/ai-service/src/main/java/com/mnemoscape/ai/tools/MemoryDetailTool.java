package com.mnemoscape.ai.tools;

import com.mnemoscape.ai.client.MemoryServiceClient;
import com.mnemoscape.ai.tools.audit.Tool;
import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 查询单条记忆的完整内容工具。
 *
 * <p>典型触发：用户问"那条 2023 在大理的记忆里我写了什么？"或者"详细告诉我
 * id=xxx 的记忆里有哪些 fragment"。模型先用 {@code milvusSearchTool} 拿到候选
 * memoryId，再用本工具拉详情 + 必要时拉 fragments。
 *
 * <p>权限：memory-service 在 {@code GET /memories/{id}} 中已经按 X-User-Id
 * 做了 PRIVATE/FRIENDS/PUBLIC 校验，本工具只透传不绕权限。
 *
 * <p>返回的 detail 字段刻意做白名单（不含 visualData / audioData 等大块二进制
 * JSON），保持 prompt token 经济：模型其实不需要把 SceneData 三十几 KB JSON
 * 全塞进 context，只需要"标题 / 描述 / 时间地点 / 是否已落地坐标"等元信息。
 */
@Configuration
public class MemoryDetailTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryDetailTool.class);

    public static class Request {
        /** 必填，由 milvusSearchTool 返回的 memoryId 直接传入。 */
        public String memoryId;
        /** 是否同时取 fragments；默认 false。 */
        public Boolean includeFragments;
    }

    public static class Detail {
        public String id;
        public String title;
        public String description;
        public String location;
        public Integer year;
        public String season;
        public String timeOfDay;
        public String privacyLevel;
        public Double fadeLevel;
        public Boolean isLocked;
        public Double lng;
        public Double lat;
        public String createdAt;
        public String updatedAt;
        public List<Map<String, Object>> fragments;
    }

    public static class Response {
        public Detail detail;
        public boolean degraded = false;
        public String message;
    }

    private final MemoryServiceClient memoryClient;

    public MemoryDetailTool(MemoryServiceClient memoryClient) {
        this.memoryClient = memoryClient;
    }

    @Bean
    public FunctionCallback memoryDetailToolCallback() {
        Function<Request, Response> fn = this::lookup;
        return FunctionCallback.builder()
                .description("Fetch the full detail of ONE specific memory by id (the user's own "
                        + "or one shared with them). Returns title, description, time/place, "
                        + "privacy, fade level, and optionally the discovered fragments. "
                        + "Use AFTER milvusSearchTool when you need the actual content of a "
                        + "matched memory, or when the user names a specific memory id.")
                .function("memoryDetailTool", fn)
                .inputType(Request.class)
                .build();
    }

    @Tool("memoryDetailTool")
    public Response lookup(Request req) {
        Response resp = new Response();
        if (req == null || req.memoryId == null || req.memoryId.isBlank()) {
            resp.degraded = true;
            resp.message = "memoryId required";
            return resp;
        }
        String userId = MilvusSearchTool.currentUserId();
        if (userId == null) {
            resp.degraded = true;
            resp.message = "Unauthenticated";
            return resp;
        }

        try {
            ApiResponse<Map<String, Object>> raw = memoryClient.getMemory(req.memoryId, userId);
            if (raw == null || raw.getData() == null) {
                resp.degraded = true;
                resp.message = "Memory not found or access denied";
                return resp;
            }
            resp.detail = projectDetail(raw.getData());

            if (Boolean.TRUE.equals(req.includeFragments)) {
                try {
                    ApiResponse<List<Map<String, Object>>> fragRaw =
                            memoryClient.getFragments(req.memoryId, userId);
                    resp.detail.fragments = fragRaw == null ? List.of()
                            : projectFragments(fragRaw.getData());
                } catch (Exception fe) {
                    log.debug("memoryDetailTool fragments fetch skipped: {}", fe.getMessage());
                }
            }
            return resp;
        } catch (feign.FeignException.NotFound nf) {
            resp.degraded = true;
            resp.message = "Memory not found";
            return resp;
        } catch (feign.FeignException.Forbidden fb) {
            resp.degraded = true;
            resp.message = "Access denied";
            return resp;
        } catch (Exception e) {
            log.warn("memoryDetailTool degraded: {}", e.toString());
            resp.degraded = true;
            resp.message = "memory-service error: " + e.getClass().getSimpleName();
            return resp;
        }
    }

    private static Detail projectDetail(Map<String, Object> raw) {
        Detail d = new Detail();
        d.id = str(raw.get("id"));
        d.title = str(raw.get("title"));
        // description 可能很长 — 截断到 600 字以内即可（模型不需要每个字都读）
        String desc = str(raw.get("description"));
        d.description = desc.length() > 600 ? desc.substring(0, 600) + "…" : desc;
        d.location = str(raw.get("memoryLocation"));
        d.year = raw.get("memoryYear") instanceof Number n ? n.intValue() : null;
        d.season = str(raw.get("memorySeason"));
        d.timeOfDay = str(raw.get("memoryTimeOfDay"));
        d.privacyLevel = str(raw.get("privacyLevel"));
        d.fadeLevel = raw.get("fadeLevel") instanceof Number n ? n.doubleValue() : null;
        d.isLocked = raw.get("isLocked") instanceof Boolean b ? b : null;
        d.lng = raw.get("memoryLng") instanceof Number n ? n.doubleValue() : null;
        d.lat = raw.get("memoryLat") instanceof Number n ? n.doubleValue() : null;
        d.createdAt = str(raw.get("createdAt"));
        d.updatedAt = str(raw.get("updatedAt"));
        return d;
    }

    private static List<Map<String, Object>> projectFragments(List<Map<String, Object>> rows) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (rows == null) return out;
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", str(r.get("id")));
            m.put("fragmentType", str(r.get("fragmentType")));
            String c = str(r.get("content"));
            m.put("content", c.length() > 240 ? c.substring(0, 240) + "…" : c);
            m.put("isDiscovered", r.get("isDiscovered"));
            out.add(m);
        }
        return out;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** 单测辅助。 */
    public static Map<String, Object> detailToMap(Detail d) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.id);
        m.put("title", d.title);
        m.put("location", d.location);
        return m;
    }
}
