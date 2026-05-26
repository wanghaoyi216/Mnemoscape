package com.mnemoscape.memory.client;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.memory.model.dto.EntityExtractionResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Map;

@FeignClient(name = "ai-service")
public interface AiServiceClient {

    @PostMapping("/api/v1/reconstruct")
    Map<String, Object> reconstruct(@RequestBody Map<String, Object> request);

    /**
     * 从描述里提取人物 / 地点 / 物件 / 情绪标签。
     * 与 ai-service {@code /api/v1/extract} 的契约对齐。
     */
    @PostMapping("/api/v1/extract")
    ApiResponse<EntityExtractionResponse> extractEntities(@RequestBody Map<String, Object> request);
}
