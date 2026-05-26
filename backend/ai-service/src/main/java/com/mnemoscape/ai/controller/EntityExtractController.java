package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.model.dto.EntityExtractRequest;
import com.mnemoscape.ai.model.dto.EntityExtractResponse;
import com.mnemoscape.ai.service.EntityExtractor;
import com.mnemoscape.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * POST /api/v1/extract —— 从记忆描述里提取人物 / 地点 / 物件 / 情绪。
 *
 * <p>当前后端是 {@link EntityExtractor} 的规则版；未来同接口可平滑替换为
 * LLM 实现。Feign 调用方（memory-service）的契约不会变动。
 */
@RestController
@RequestMapping("/api/v1/extract")
public class EntityExtractController {

    private final EntityExtractor extractor;

    public EntityExtractController(EntityExtractor extractor) {
        this.extractor = extractor;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EntityExtractResponse>> extract(
            @Valid @RequestBody EntityExtractRequest request) {
        return ResponseEntity.ok(ApiResponse.success(extractor.extract(request)));
    }
}
