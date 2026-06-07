package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.service.VectorIndexService;
import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆向量索引端点 — 供 memory-service 在记忆创建 / 重建 / 删除时调用。
 *
 * <p>设计为"服务间内部端点"：memory-service 通过 Feign（{@code AiServiceClient}）
 * 调用。所有操作 best-effort：即便 Milvus / Embedding 不可用也返回 200 + indexed=false，
 * 让 memory-service 的主流程（创建 / 删除记忆）永不因向量索引失败而中断。
 *
 * <p>身份：{@code X-User-Id} 由网关注入；索引时强制写进向量的 {@code user_id}
 * 标量字段，检索时按它过滤，保证多租户隔离。
 */
@RestController
@RequestMapping("/api/v1/vector")
public class VectorIndexController {

    private final VectorIndexService indexService;

    public VectorIndexController(VectorIndexService indexService) {
        this.indexService = indexService;
    }

    /** 索引请求体。memory-service 把记忆的结构化字段透传过来。 */
    public static class IndexRequest {
        public String memoryId;
        public String userId;
        public String title;
        public String location;
        public Integer year;
        public String description;
        /** 隐私级别（PUBLIC / FRIENDS / PRIVATE）；共鸣大厅公共检索按它过滤。 */
        public String privacy;
    }

    /**
     * Upsert 一条记忆向量。
     * userId 优先取 body（memory-service 已知真实归属），否则回退 X-User-Id 头。
     */
    @PostMapping("/index")
    public ResponseEntity<ApiResponse<Map<String, Object>>> index(
            @RequestBody IndexRequest req,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String userId = (req.userId != null && !req.userId.isBlank()) ? req.userId : headerUserId;
        boolean ok = indexService.index(req.memoryId, userId, req.title,
                req.location, req.year, req.description, req.privacy);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memoryId", req.memoryId);
        data.put("indexed", ok);
        data.put("ready", indexService.isReady());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** 共鸣大厅公共向量检索请求体。 */
    public static class PublicSearchRequest {
        public String seedText;
        public String excludeUserId;
        public Integer topK;
    }

    /**
     * 跨用户公共向量检索（共鸣大厅用）。只召回 PUBLIC 记忆并排除 excludeUserId。
     * 返回 {@code available=false} 时调用方应降级到关键词打分。
     */
    @PostMapping("/search-public")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchPublic(
            @RequestBody PublicSearchRequest req) {
        int topK = req.topK == null ? 8 : req.topK;
        var hits = indexService.searchPublic(req.seedText, req.excludeUserId, topK);
        Map<String, Object> data = new LinkedHashMap<>();
        if (hits == null) {
            data.put("available", false);
            data.put("hits", java.util.List.of());
        } else {
            data.put("available", true);
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (var h : hits) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("memoryId", h.memoryId);
                m.put("userId", h.userId);
                m.put("title", h.title);
                m.put("location", h.location);
                m.put("year", h.year);
                m.put("snippet", h.snippet);
                m.put("score", h.score);
                out.add(m);
            }
            data.put("hits", out);
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** 删除一条记忆向量。 */
    @DeleteMapping("/index/{memoryId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable String memoryId) {
        boolean ok = indexService.delete(memoryId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memoryId", memoryId);
        data.put("deleted", ok);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
