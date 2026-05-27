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
