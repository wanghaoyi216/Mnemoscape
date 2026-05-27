package com.mnemoscape.resonance.client;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Feign client to memory-service for resonance真实化检索。
 *
 * <p>Two surface points used by {@link com.mnemoscape.resonance.service.ResonanceService}:
 * <ul>
 *   <li>{@code GET /memories/{id}} — 取调用方的 seed 记忆，作为相似度比对的"自我"端</li>
 *   <li>{@code GET /memories/public-pool} — 跨用户公共记忆池（排除 caller），
 *       用于在真实数据上做关键词重叠 + 季节/年代/地点的轻量相似度打分</li>
 * </ul>
 *
 * <p>所有请求都把 {@code X-User-Id} 透传给 memory-service，让其原有的隐私过滤
 * （PRIVATE / FRIENDS / PUBLIC）继续生效；本服务<b>绝不绕过</b>。
 */
@FeignClient(name = "memory-service", contextId = "memoryServiceForResonance", path = "/api/v1")
public interface MemoryServiceClient {

    @GetMapping("/memories/{id}")
    ApiResponse<Map<String, Object>> getMemory(@PathVariable("id") String id,
                                               @RequestHeader("X-User-Id") String userId);

    @GetMapping("/memories/public-pool")
    ApiResponse<List<Map<String, Object>>> publicPool(
            @RequestParam(value = "limit", defaultValue = "200") int limit,
            @RequestHeader("X-User-Id") String userId);
}
