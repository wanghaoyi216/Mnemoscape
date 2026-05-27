package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.model.dto.ReconstructRequest;
import com.mnemoscape.ai.model.dto.SceneDataRequest;
import com.mnemoscape.ai.model.dto.SceneEnhancementResponse;
import com.mnemoscape.ai.model.dto.SceneGapFillResponse;
import com.mnemoscape.ai.model.dto.SceneReconstructionResponse;
import com.mnemoscape.ai.service.ReconstructDispatcher;
import com.mnemoscape.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 场景重建端点 — 接前端 SceneViewer。
 *
 * <p>v2 改造：把直接依赖 {@code MockReconstructService} 改为依赖
 * {@link ReconstructDispatcher}，由 dispatcher 负责"LLM 优先 + 规则版兜底"
 * 的双轨策略。前端契约不变。
 */
@RestController
@RequestMapping("/api/v1/reconstruct")
public class ReconstructController {

    private final ReconstructDispatcher dispatcher;

    public ReconstructController(ReconstructDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SceneReconstructionResponse>> reconstruct(
            @Valid @RequestBody ReconstructRequest request) {
        return ResponseEntity.ok(ApiResponse.success(dispatcher.reconstruct(request)));
    }

    @PostMapping("/enhance")
    public ResponseEntity<ApiResponse<SceneEnhancementResponse>> enhance(
            @Valid @RequestBody SceneDataRequest request) {
        return ResponseEntity.ok(ApiResponse.success(dispatcher.enhance(request.getSceneData())));
    }

    @PostMapping("/fill-gaps")
    public ResponseEntity<ApiResponse<SceneGapFillResponse>> fillGaps(
            @Valid @RequestBody SceneDataRequest request) {
        return ResponseEntity.ok(ApiResponse.success(dispatcher.fillGaps(request.getSceneData())));
    }
}
