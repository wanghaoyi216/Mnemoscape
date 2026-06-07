package com.mnemoscape.ai.model.dto;

import java.util.List;

public class SceneData {
    private String environment;
    private SceneLighting lighting;
    private SceneTerrain terrain;
    private SceneAtmosphere atmosphere;
    private List<SceneObject> objects;
    private SceneAudioData audioData;
    private List<SceneFragment> fragments;

    public SceneData() {
    }

    public SceneData(String environment, SceneLighting lighting, SceneTerrain terrain, SceneAtmosphere atmosphere,
                     List<SceneObject> objects, SceneAudioData audioData, List<SceneFragment> fragments) {
        this.environment = environment;
        this.lighting = lighting;
        this.terrain = terrain;
        this.atmosphere = atmosphere;
        this.objects = objects;
        this.audioData = audioData;
        this.fragments = fragments;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public SceneLighting getLighting() {
        return lighting;
    }

    public void setLighting(SceneLighting lighting) {
        this.lighting = lighting;
    }

    public SceneTerrain getTerrain() {
        return terrain;
    }

    public void setTerrain(SceneTerrain terrain) {
        this.terrain = terrain;
    }

    public SceneAtmosphere getAtmosphere() {
        return atmosphere;
    }

    public void setAtmosphere(SceneAtmosphere atmosphere) {
        this.atmosphere = atmosphere;
    }

    public List<SceneObject> getObjects() {
        return objects;
    }

    public void setObjects(List<SceneObject> objects) {
        this.objects = objects;
    }

    public SceneAudioData getAudioData() {
        return audioData;
    }

    public void setAudioData(SceneAudioData audioData) {
        this.audioData = audioData;
    }

    public List<SceneFragment> getFragments() {
        return fragments;
    }

    public void setFragments(List<SceneFragment> fragments) {
        this.fragments = fragments;
    }
}
