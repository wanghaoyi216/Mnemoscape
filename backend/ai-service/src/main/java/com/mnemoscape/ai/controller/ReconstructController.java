package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.model.dto.ReconstructRequest;
import com.mnemoscape.ai.model.dto.SceneDataRequest;
import com.mnemoscape.ai.model.dto.SceneEnhancementResponse;
import com.mnemoscape.ai.model.dto.SceneGapFillResponse;
import com.mnemoscape.ai.model.dto.SceneReconstructionResponse;
import com.mnemoscape.ai.service.MockReconstructService;
import com.mnemoscape.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reconstruct")
public class ReconstructController {
    private final MockReconstructService mockService;

    public ReconstructController(MockReconstructService mockService) {
        this.mockService = mockService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SceneReconstructionResponse>> reconstruct(
            @Valid @RequestBody ReconstructRequest request) {
        return ResponseEntity.ok(ApiResponse.success(mockService.reconstruct(request.getDescription())));
    }

    @PostMapping("/enhance")
    public ResponseEntity<ApiResponse<SceneEnhancementResponse>> enhance(
            @Valid @RequestBody SceneDataRequest request) {
        return ResponseEntity.ok(ApiResponse.success(mockService.enhance(request.getSceneData())));
    }

    @PostMapping("/fill-gaps")
    public ResponseEntity<ApiResponse<SceneGapFillResponse>> fillGaps(
            @Valid @RequestBody SceneDataRequest request) {
        return ResponseEntity.ok(ApiResponse.success(mockService.fillGaps(request.getSceneData())));
    }
}
