package com.mnemoscape.ai.model.dto;

public class SceneLighting {
    private String type;
    private String color;
    private double intensity;

    public SceneLighting() {
    }

    public SceneLighting(String type, String color, double intensity) {
        this.type = type;
        this.color = color;
        this.intensity = intensity;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public double getIntensity() {
        return intensity;
    }

    public void setIntensity(double intensity) {
        this.intensity = intensity;
    }
}
