package com.mnemoscape.ai.model.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SceneData {
    private String environment;
    private SceneLighting lighting;
    private SceneTerrain terrain;
    private SceneAtmosphere atmosphere;
    private List<SceneObject> objects;
    private SceneAudioData audioData;
    private List<SceneFragment> fragments;
}
