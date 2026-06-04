package com.mnemoscape.resonance.controller;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import com.mnemoscape.resonance.model.entity.MemoryNote;
import com.mnemoscape.resonance.model.entity.ResonanceSpace;
import com.mnemoscape.resonance.service.ResonanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resonances")
public class ResonanceController {
    private final ResonanceService resonanceService;

    public ResonanceController(ResonanceService resonanceService) {
        this.resonanceService = resonanceService;
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(
            @RequestParam String memoryId,
            HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        return ResponseEntity.ok(ApiResponse.success(resonanceService.searchResonances(memoryId, userId)));
    }

    /**
     * 共鸣服务聚合统计（用于 ResonanceHub 顶部三张 metric 卡片）。
     *
     * <p>返回当前用户的：
     * <ul>
     *   <li>{@code avgScore} — 历史共鸣匹配的平均相似度（0..1）</li>
     *   <li>{@code totalMatches} — 跨用户公共记忆池中可被召回的候选数量</li>
     *   <li>{@code algorithmName} — 当前打分算法的人类可读标识</li>
     * </ul>
     *
     * <p>实现：复用 {@link ResonanceService#searchResonances} 的 public-pool 拉取 + 关键词打分
     * 路径，再做一层聚合；不重复打分逻辑以保持统计与实时检索一致。
     * 真实平均分按"至少被命中过的记忆"加全后求均值；当前若公共池为空则返回 0。
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Map<String, Object> stats = resonanceService.resonanceStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @PostMapping("/spaces")
    public ResponseEntity<ApiResponse<ResonanceSpace>> createSpace(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(resonanceService.createSpace(
                        body.get("memoryId1"), body.get("memoryId2"), userId)));
    }

    @GetMapping("/spaces/{id}")
    public ResponseEntity<ApiResponse<ResonanceSpace>> getSpace(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(resonanceService.getSpace(id)));
    }

    @GetMapping("/spaces/{id}/notes")
    public ResponseEntity<ApiResponse<List<MemoryNote>>> getNotes(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(resonanceService.getNotes(id)));
    }

    @PostMapping("/spaces/{id}/notes")
    public ResponseEntity<ApiResponse<MemoryNote>> placeNote(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String authorId = RequestContext.requireUserId(request);
        String content = (String) body.get("content");
        String mood = (String) body.get("mood");
        @SuppressWarnings("unchecked")
        Map<String, Double> position = (Map<String, Double>) body.get("position");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(resonanceService.placeNote(id, authorId, content, mood, position)));
    }
}
