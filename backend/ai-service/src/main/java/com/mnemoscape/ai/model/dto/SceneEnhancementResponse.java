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
public class SceneEnhancementResponse {
    private SceneData sceneData;
    private boolean enhanced;
    private List<SceneObject> addedObjects;
}
