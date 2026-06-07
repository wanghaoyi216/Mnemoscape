package com.mnemoscape.ai.model.dto;

import java.util.List;

public class SceneObject {
    private String id;
    private String type;
    private String name;
    private List<Double> position;
    private String color;
    private List<Double> scale;

    public SceneObject() {
    }

    public SceneObject(String id, String type, String name, List<Double> position, String color, List<Double> scale) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.position = position;
        this.color = color;
        this.scale = scale;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Double> getPosition() {
        return position;
    }

    public void setPosition(List<Double> position) {
        this.position = position;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public List<Double> getScale() {
        return scale;
    }

    public void setScale(List<Double> scale) {
        this.scale = scale;
    }
}
