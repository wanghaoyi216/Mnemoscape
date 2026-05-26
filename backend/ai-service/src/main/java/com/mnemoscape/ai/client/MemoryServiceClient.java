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
}
