package com.mnemoscape.ai.model.dto;

import java.util.List;

public class SceneEnhancementResponse {
    private SceneData sceneData;
    private boolean enhanced;
    private List<SceneObject> addedObjects;

    public SceneEnhancementResponse() {
    }

    public SceneEnhancementResponse(SceneData sceneData, boolean enhanced, List<SceneObject> addedObjects) {
        this.sceneData = sceneData;
        this.enhanced = enhanced;
        this.addedObjects = addedObjects;
    }

    public SceneData getSceneData() {
        return sceneData;
    }

    public void setSceneData(SceneData sceneData) {
        this.sceneData = sceneData;
    }

    public boolean isEnhanced() {
        return enhanced;
    }

    public void setEnhanced(boolean enhanced) {
        this.enhanced = enhanced;
    }

    public List<SceneObject> getAddedObjects() {
        return addedObjects;
    }

    public void setAddedObjects(List<SceneObject> addedObjects) {
        this.addedObjects = addedObjects;
    }
}
