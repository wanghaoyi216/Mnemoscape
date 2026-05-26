package com.mnemoscape.ai.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class SceneDataRequest {
    @NotNull(message = "sceneData is required")
    @Valid
    private SceneData sceneData;

    public SceneDataRequest() {
    }

    public SceneDataRequest(SceneData sceneData) {
        this.sceneData = sceneData;
    }

    public SceneData getSceneData() {
        return sceneData;
    }

    public void setSceneData(SceneData sceneData) {
        this.sceneData = sceneData;
    }
}
