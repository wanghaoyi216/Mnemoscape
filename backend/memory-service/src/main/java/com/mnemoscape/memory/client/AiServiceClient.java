package com.mnemoscape.memory.client;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.memory.model.dto.EntityExtractionResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 索引（upsert）一条记忆向量到 Milvus。
     *
     * <p>best-effort：ai-service 即便 Embedding / Milvus 不可用也返回 200 +
     * {@code indexed=false}，所以调用方只需 try/catch Feign 自身的网络异常。
     * 请求体字段：memoryId / userId / title / location / year / description。
     */
    @PostMapping("/api/v1/vector/index")
    ApiResponse<Map<String, Object>> indexVector(@RequestBody Map<String, Object> request);

    /** 删除一条记忆向量（记忆被删除时调用）。best-effort。 */
    @DeleteMapping("/api/v1/vector/index/{memoryId}")
    ApiResponse<Map<String, Object>> deleteVector(@PathVariable("memoryId") String memoryId);
}
