package com.mnemoscape.memory.controller;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import com.mnemoscape.memory.model.entity.DriftBottle;
import com.mnemoscape.memory.service.DriftBottleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/bottles")
public class DriftBottleController {

    private final DriftBottleService bottleService;

    public DriftBottleController(DriftBottleService bottleService) {
        this.bottleService = bottleService;
    }

    @PostMapping("/throw")
    public ResponseEntity<ApiResponse<Map<String, Object>>> throwBottle(
            @RequestBody Map<String, String> body,
            HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        String memoryId = body.get("memoryId");
        String snippet = body.get("snippet");

        if (memoryId == null || snippet == null || snippet.length() > 500) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("Invalid request"));
        }

        DriftBottle bottle = bottleService.throwBottle(userId, memoryId, snippet);
        return ResponseEntity.ok(ApiResponse.success(toMap(bottle)));
    }

    @PostMapping("/pick")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pickBottle(HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        Optional<DriftBottle> bottle = bottleService.pickRandomBottle(userId);

        if (bottle.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("message", "No bottles available");
            return ResponseEntity.ok(ApiResponse.success(empty));
        }

        return ResponseEntity.ok(ApiResponse.success(toMap(bottle.get())));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyBottles(HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        List<DriftBottle> bottles = bottleService.getMyBottles(userId);
        List<Map<String, Object>> result = bottles.stream().map(this::toMap).toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/picked")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPickedBottles(HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        List<DriftBottle> bottles = bottleService.getPickedBottles(userId);
        List<Map<String, Object>> result = bottles.stream().map(this::toMap).toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private Map<String, Object> toMap(DriftBottle b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("memoryId", b.getMemoryId());
        m.put("snippet", b.getSnippet());
        m.put("emotion", b.getEmotion());
        m.put("location", b.getLocation());
        m.put("year", b.getYear());
        m.put("thrownAt", b.getThrownAt().toString());
        if (b.getPickedAt() != null) {
            m.put("pickedAt", b.getPickedAt().toString());
        }
        return m;
    }
}
