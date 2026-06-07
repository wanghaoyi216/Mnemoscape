package com.mnemoscape.ai.model.dto;

import java.util.List;

public class SceneAudioData {
    private List<SceneAudioSource> ambient;
    private List<SceneAudioSource> positional;

    public SceneAudioData() {
    }

    public SceneAudioData(List<SceneAudioSource> ambient, List<SceneAudioSource> positional) {
        this.ambient = ambient;
        this.positional = positional;
    }

    public List<SceneAudioSource> getAmbient() {
        return ambient;
    }

    public void setAmbient(List<SceneAudioSource> ambient) {
        this.ambient = ambient;
    }

    public List<SceneAudioSource> getPositional() {
        return positional;
    }

    public void setPositional(List<SceneAudioSource> positional) {
        this.positional = positional;
    }
}
