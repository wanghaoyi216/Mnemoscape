package com.mnemoscape.ai.client;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Feign client to memory-service.
 *
 * <p>Used by {@code MilvusSearchTool} as the data backbone: until a real
 * Milvus / DashVector deployment is wired up, scoring runs against the
 * authenticated user's actual memory rows. The shape of the returned
 * {@code Map} mirrors the existing {@code PageResult<MemoryResponse>}.
 *
 * <p>The {@code X-User-Id} header is forwarded from the AI request's
 * Spring Security principal so memory-service applies its existing
 * privacy filter — we never bypass user scoping.
 */
@FeignClient(name = "memory-service", contextId = "memoryService", path = "/api/v1")
public interface MemoryServiceClient {

    @GetMapping("/memories")
    ApiResponse<Map<String, Object>> listMemories(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestHeader("X-User-Id") String userId);

    @GetMapping("/memories/route")
    ApiResponse<Object> getRoute(@RequestHeader("X-User-Id") String userId);

    /**
     * 查记忆完整内容（含 description / visualData / fragments 等），AI 工具
     * MemoryDetailTool 用。memory-service 在 GET /memories/{id} 中按 X-User-Id
     * 做了完整 ACL（PRIVATE/FRIENDS/PUBLIC）— 这里只透传，不绕。
     */
    @GetMapping("/memories/{id}")
    ApiResponse<Map<String, Object>> getMemory(
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @RequestHeader("X-User-Id") String userId);

    /** 查记忆的所有 fragments，AI 工具 MemoryFragmentsTool 用。 */
    @GetMapping("/memories/{id}/fragments")
    ApiResponse<java.util.List<Map<String, Object>>> getFragments(
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @RequestHeader("X-User-Id") String userId);

    /** 查记忆的版本历史，AI 工具 MemoryHistoryTool 用。 */
    @GetMapping("/memories/{id}/versions")
    ApiResponse<java.util.List<Map<String, Object>>> getVersions(
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @RequestHeader("X-User-Id") String userId);

    /** 查 drift 状态（褪色等级 / 距离上次访问时间），AI 可用其判断哪些记忆"快被遗忘了"。 */
    @GetMapping("/memories/{id}/drift")
    ApiResponse<Map<String, Object>> getDrift(
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @RequestHeader("X-User-Id") String userId);
}
