package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.model.dto.EntityExtractRequest;
import com.mnemoscape.ai.model.dto.EntityExtractResponse;
import com.mnemoscape.ai.service.EntityExtractor;
import com.mnemoscape.ai.service.LlmEntityExtractor;
import com.mnemoscape.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/**
 * POST /api/v1/extract —— 从记忆描述里提取人物 / 地点 / 物件 / 情绪。
 *
 * <p>v2 改造：双轨。先 LLM（{@link LlmEntityExtractor}）做高质量 NER，
 * 任何失败 → 透明降级到规则版（{@link EntityExtractor}）。
 * Feign 调用方（memory-service）契约不变。
 *
 * <p>路由模式（{@code mnemoscape.ai.extract.mode}）：
 * <ul>
 *   <li>{@code auto}（默认）：先 LLM 失败降规则</li>
 *   <li>{@code llm}：强制 LLM</li>
 *   <li>{@code rule}：强制规则版（离线 / 无 key）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/extract")
public class EntityExtractController {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractController.class);

    enum Mode { AUTO, LLM, RULE }

    private final EntityExtractor ruleExtractor;
    private final LlmEntityExtractor llmExtractor;
    private final Mode mode;

    public EntityExtractController(EntityExtractor ruleExtractor,
                                    LlmEntityExtractor llmExtractor,
                                    @Value("${mnemoscape.ai.extract.mode:auto}") String modeStr) {
        this.ruleExtractor = ruleExtractor;
        this.llmExtractor = llmExtractor;
        this.mode = parseMode(modeStr);
        log.info("[EntityExtractController] mode={}", this.mode);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EntityExtractResponse>> extract(
            @Valid @RequestBody EntityExtractRequest request) {
        if (mode == Mode.RULE) {
            return ResponseEntity.ok(ApiResponse.success(ruleExtractor.extract(request)));
        }
        try {
            return ResponseEntity.ok(ApiResponse.success(llmExtractor.extract(request)));
        } catch (Exception e) {
            if (mode == Mode.LLM) throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            log.warn("[EntityExtractController] LLM extract failed; degrading to rule-based: {}",
                    e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(ruleExtractor.extract(request)));
        }
    }

    private static Mode parseMode(String s) {
        if (s == null) return Mode.AUTO;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "llm" -> Mode.LLM;
            case "rule", "rules", "rule-based" -> Mode.RULE;
            default -> Mode.AUTO;
        };
    }
}
