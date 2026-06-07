package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.service.IntentHintService;
import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态推荐问题端点 — 给 AI 浮窗（AiMascotDock）提供个性化快捷短语，
 * 取代前端硬编码的 4 条静态 hint。
 *
 * <p>请求体直接复用前端已经在构建的 memory digest（title / location / year），
 * 因此无需跨服务 fan-out 去拉 memory-service。locale 控制中英文。
 *
 * <p>挂在 {@code /api/v1/reconstruct/...} 前缀下，复用网关已有的 ai-service 路由。
 */
@RestController
@RequestMapping("/api/v1/reconstruct/chat")
public class IntentHintController {

    private final IntentHintService hintService;

    public IntentHintController(IntentHintService hintService) {
        this.hintService = hintService;
    }

    public static class HintRequest {
        public String locale;
        public List<DigestDto> context;
    }

    public static class DigestDto {
        public String title;
        public String location;
        public Integer year;
    }

    @PostMapping("/hints")
    public ResponseEntity<ApiResponse<Map<String, Object>>> hints(@RequestBody(required = false) HintRequest req) {
        boolean zh = req == null || req.locale == null || !req.locale.toLowerCase().startsWith("en");
        List<IntentHintService.MemoryDigest> digests = new ArrayList<>();
        if (req != null && req.context != null) {
            for (DigestDto d : req.context) {
                if (d == null) continue;
                digests.add(new IntentHintService.MemoryDigest(d.title, d.location, d.year));
            }
        }
        List<String> hints = hintService.generate(digests, zh);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hints", hints);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
