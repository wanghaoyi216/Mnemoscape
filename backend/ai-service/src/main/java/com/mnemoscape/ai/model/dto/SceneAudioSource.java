package com.mnemoscape.ai.model.dto;

import java.util.List;

public class SceneAudioSource {
    private String type;
    private String file;
    private double volume;
    private Boolean loop;
    private List<Double> position;

    public SceneAudioSource() {
    }

    public SceneAudioSource(String type, String file, double volume, Boolean loop, List<Double> position) {
        this.type = type;
        this.file = file;
        this.volume = volume;
        this.loop = loop;
        this.position = position;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public Boolean getLoop() {
        return loop;
    }

    public void setLoop(Boolean loop) {
        this.loop = loop;
    }

    public List<Double> getPosition() {
        return position;
    }

    public void setPosition(List<Double> position) {
        this.position = position;
    }
}
