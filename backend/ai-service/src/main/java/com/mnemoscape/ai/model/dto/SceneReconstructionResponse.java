package com.mnemoscape.ai.model.dto;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SceneReconstructionResponse {
    private SceneData sceneData;
    private SceneAudioData audioData;
    private Map<String, Double> emotionVector;
    private Map<String, String> sensoryDetails;
    private List<SceneFragment> fragments;
    private String sceneDataUrl;
}
