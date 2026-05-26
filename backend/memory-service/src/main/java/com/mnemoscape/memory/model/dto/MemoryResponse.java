package com.mnemoscape.memory.model.dto;

import com.mnemoscape.memory.model.entity.Memory;

public class MemoryResponse {
    private String id;
    private String userId;
    private String title;
    private String description;
    private Integer memoryYear;
    private String memoryDate;
    private String memorySeason;
    private String memoryTimeOfDay;
    private String memoryLocation;
    /** [lng, lat] when geocoded; null otherwise */
    private double[] coords;
    private String privacyLevel;
    private boolean isLocked;
    private double fadeLevel;
    private String sceneDataUrl;
    private String emotionVectorId;
    private String createdAt;
    private String updatedAt;

    public MemoryResponse() {}

    public static MemoryResponse fromEntity(Memory memory) {
        MemoryResponse response = new MemoryResponse();
        response.id = memory.getId();
        response.userId = memory.getUserId();
        response.title = memory.getTitle();
        response.description = memory.getDescription();
        response.memoryYear = memory.getMemoryYear();
        response.memoryDate = memory.getMemoryDate() != null ? memory.getMemoryDate().toString() : null;
        response.memorySeason = memory.getMemorySeason();
        response.memoryTimeOfDay = memory.getMemoryTimeOfDay();
        response.memoryLocation = memory.getMemoryLocation();
        if (memory.getMemoryLng() != null && memory.getMemoryLat() != null) {
            response.coords = new double[] { memory.getMemoryLng(), memory.getMemoryLat() };
        }
        response.privacyLevel = memory.getPrivacyLevel() != null ? memory.getPrivacyLevel().name() : null;
        response.isLocked = memory.getIsLocked() != null && memory.getIsLocked();
        response.fadeLevel = memory.getFadeLevel() != null ? memory.getFadeLevel() : 0.0;
        response.sceneDataUrl = memory.getSceneDataUrl();
        response.emotionVectorId = memory.getEmotionVectorId();
        response.createdAt = memory.getCreatedAt() != null ? memory.getCreatedAt().toString() : null;
        response.updatedAt = memory.getUpdatedAt() != null ? memory.getUpdatedAt().toString() : null;
        return response;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getMemoryYear() { return memoryYear; }
    public void setMemoryYear(Integer memoryYear) { this.memoryYear = memoryYear; }
    public String getMemoryDate() { return memoryDate; }
    public void setMemoryDate(String memoryDate) { this.memoryDate = memoryDate; }
    public String getMemorySeason() { return memorySeason; }
    public void setMemorySeason(String memorySeason) { this.memorySeason = memorySeason; }
    public String getMemoryTimeOfDay() { return memoryTimeOfDay; }
    public void setMemoryTimeOfDay(String memoryTimeOfDay) { this.memoryTimeOfDay = memoryTimeOfDay; }
    public String getMemoryLocation() { return memoryLocation; }
    public void setMemoryLocation(String memoryLocation) { this.memoryLocation = memoryLocation; }
    public double[] getCoords() { return coords; }
    public void setCoords(double[] coords) { this.coords = coords; }
    public String getPrivacyLevel() { return privacyLevel; }
    public void setPrivacyLevel(String privacyLevel) { this.privacyLevel = privacyLevel; }
    public boolean getIsLocked() { return isLocked; }
    public void setIsLocked(boolean isLocked) { this.isLocked = isLocked; }
    public double getFadeLevel() { return fadeLevel; }
    public void setFadeLevel(double fadeLevel) { this.fadeLevel = fadeLevel; }
    public String getSceneDataUrl() { return sceneDataUrl; }
    public void setSceneDataUrl(String sceneDataUrl) { this.sceneDataUrl = sceneDataUrl; }
    public String getEmotionVectorId() { return emotionVectorId; }
    public void setEmotionVectorId(String emotionVectorId) { this.emotionVectorId = emotionVectorId; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
