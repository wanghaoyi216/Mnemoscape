package com.mnemoscape.ai.model.dto;

import java.util.List;
import java.util.Map;

public class SceneReconstructionResponse {
    private SceneData sceneData;
    private SceneAudioData audioData;
    private Map<String, Double> emotionVector;
    private Map<String, String> sensoryDetails;
    private List<SceneFragment> fragments;
    private String sceneDataUrl;

    public SceneReconstructionResponse() {
    }

    public SceneReconstructionResponse(SceneData sceneData, SceneAudioData audioData, Map<String, Double> emotionVector,
                                       Map<String, String> sensoryDetails, List<SceneFragment> fragments, String sceneDataUrl) {
        this.sceneData = sceneData;
        this.audioData = audioData;
        this.emotionVector = emotionVector;
        this.sensoryDetails = sensoryDetails;
        this.fragments = fragments;
        this.sceneDataUrl = sceneDataUrl;
    }

    public SceneData getSceneData() {
        return sceneData;
    }

    public void setSceneData(SceneData sceneData) {
        this.sceneData = sceneData;
    }

    public SceneAudioData getAudioData() {
        return audioData;
    }

    public void setAudioData(SceneAudioData audioData) {
        this.audioData = audioData;
    }

    public Map<String, Double> getEmotionVector() {
        return emotionVector;
    }

    public void setEmotionVector(Map<String, Double> emotionVector) {
        this.emotionVector = emotionVector;
    }

    public Map<String, String> getSensoryDetails() {
        return sensoryDetails;
    }

    public void setSensoryDetails(Map<String, String> sensoryDetails) {
        this.sensoryDetails = sensoryDetails;
    }

    public List<SceneFragment> getFragments() {
        return fragments;
    }

    public void setFragments(List<SceneFragment> fragments) {
        this.fragments = fragments;
    }

    public String getSceneDataUrl() {
        return sceneDataUrl;
    }

    public void setSceneDataUrl(String sceneDataUrl) {
        this.sceneDataUrl = sceneDataUrl;
    }
}
