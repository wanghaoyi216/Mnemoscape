package com.mnemoscape.ai.model.dto;

public class InferredDetail {
    private String type;
    private String content;
    private double confidence;

    public InferredDetail() {
    }

    public InferredDetail(String type, String content, double confidence) {
        this.type = type;
        this.content = content;
        this.confidence = confidence;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
