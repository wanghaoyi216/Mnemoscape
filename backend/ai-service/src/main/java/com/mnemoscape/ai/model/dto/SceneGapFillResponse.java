package com.mnemoscape.ai.model.dto;

import java.util.List;

public class SceneGapFillResponse {
    private SceneData sceneData;
    private boolean gapsFilled;
    private List<InferredDetail> inferredDetails;

    public SceneGapFillResponse() {
    }

    public SceneGapFillResponse(SceneData sceneData, boolean gapsFilled, List<InferredDetail> inferredDetails) {
        this.sceneData = sceneData;
        this.gapsFilled = gapsFilled;
        this.inferredDetails = inferredDetails;
    }

    public SceneData getSceneData() {
        return sceneData;
    }

    public void setSceneData(SceneData sceneData) {
        this.sceneData = sceneData;
    }

    public boolean isGapsFilled() {
        return gapsFilled;
    }

    public void setGapsFilled(boolean gapsFilled) {
        this.gapsFilled = gapsFilled;
    }

    public List<InferredDetail> getInferredDetails() {
        return inferredDetails;
    }

    public void setInferredDetails(List<InferredDetail> inferredDetails) {
        this.inferredDetails = inferredDetails;
    }
}
