package com.mnemoscape.ai.model.dto;

public class SceneAtmosphere {
    private String fogColor;
    private double fogDensity;
    private String backgroundColor;

    public SceneAtmosphere() {
    }

    public SceneAtmosphere(String fogColor, double fogDensity, String backgroundColor) {
        this.fogColor = fogColor;
        this.fogDensity = fogDensity;
        this.backgroundColor = backgroundColor;
    }

    public String getFogColor() {
        return fogColor;
    }

    public void setFogColor(String fogColor) {
        this.fogColor = fogColor;
    }

    public double getFogDensity() {
        return fogDensity;
    }

    public void setFogDensity(double fogDensity) {
        this.fogDensity = fogDensity;
    }

    public String getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(String backgroundColor) {
        this.backgroundColor = backgroundColor;
    }
}
