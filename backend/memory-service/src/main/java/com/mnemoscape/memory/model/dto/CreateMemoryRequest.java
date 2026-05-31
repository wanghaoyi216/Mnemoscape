package com.mnemoscape.memory.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateMemoryRequest {
    @NotBlank @Size(min = 5, message = "Memory description must be at least 5 characters")
    private String description;

    @NotBlank @Size(max = 200)
    private String title;

    private Integer memoryYear;
    private String memoryDate;
    private String memorySeason;
    private String memoryTimeOfDay;
    private String memoryLocation;
    /**
     * 精确坐标（可选）。当前端通过浏览器 GPS / Nominatim 正向地理编码拿到真实的
     * 街道级经纬度时一并提交，后端优先采用它，而不是再用 anchor 表把地名退化成
     * 城市中心点。这些是用户真实位置，不是客户端伪造 —— 后端仍会做范围校验。
     */
    private Double memoryLat;
    private Double memoryLng;
    @Pattern(regexp = "(?i)PRIVATE|FRIENDS|PUBLIC", message = "Privacy level must be PRIVATE, FRIENDS, or PUBLIC")
    private String privacyLevel;

    private String sceneDataUrl;

    public CreateMemoryRequest() {}

    public CreateMemoryRequest(String description, String title, Integer memoryYear, String memoryDate, String memorySeason, String memoryTimeOfDay, String memoryLocation, String privacyLevel, String sceneDataUrl) {
        this.description = description;
        this.title = title;
        this.memoryYear = memoryYear;
        this.memoryDate = memoryDate;
        this.memorySeason = memorySeason;
        this.memoryTimeOfDay = memoryTimeOfDay;
        this.memoryLocation = memoryLocation;
        this.privacyLevel = privacyLevel;
        this.sceneDataUrl = sceneDataUrl;
    }

    public static CreateMemoryRequestBuilder builder() {
        return new CreateMemoryRequestBuilder();
    }

    public static class CreateMemoryRequestBuilder {
        private String description;
        private String title;
        private Integer memoryYear;
        private String memoryDate;
        private String memorySeason;
        private String memoryTimeOfDay;
        private String memoryLocation;
        private String privacyLevel;
        private String sceneDataUrl;

        CreateMemoryRequestBuilder() {}

        public CreateMemoryRequestBuilder description(String description) { this.description = description; return this; }
        public CreateMemoryRequestBuilder title(String title) { this.title = title; return this; }
        public CreateMemoryRequestBuilder memoryYear(Integer memoryYear) { this.memoryYear = memoryYear; return this; }
        public CreateMemoryRequestBuilder memoryDate(String memoryDate) { this.memoryDate = memoryDate; return this; }
        public CreateMemoryRequestBuilder memorySeason(String memorySeason) { this.memorySeason = memorySeason; return this; }
        public CreateMemoryRequestBuilder memoryTimeOfDay(String memoryTimeOfDay) { this.memoryTimeOfDay = memoryTimeOfDay; return this; }
        public CreateMemoryRequestBuilder memoryLocation(String memoryLocation) { this.memoryLocation = memoryLocation; return this; }
        public CreateMemoryRequestBuilder privacyLevel(String privacyLevel) { this.privacyLevel = privacyLevel; return this; }
        public CreateMemoryRequestBuilder sceneDataUrl(String sceneDataUrl) { this.sceneDataUrl = sceneDataUrl; return this; }

        public CreateMemoryRequest build() {
            return new CreateMemoryRequest(description, title, memoryYear, memoryDate, memorySeason, memoryTimeOfDay, memoryLocation, privacyLevel, sceneDataUrl);
        }
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
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
    public Double getMemoryLat() { return memoryLat; }
    public void setMemoryLat(Double memoryLat) { this.memoryLat = memoryLat; }
    public Double getMemoryLng() { return memoryLng; }
    public void setMemoryLng(Double memoryLng) { this.memoryLng = memoryLng; }
    public String getPrivacyLevel() { return privacyLevel; }
    public void setPrivacyLevel(String privacyLevel) { this.privacyLevel = privacyLevel; }
    public String getSceneDataUrl() { return sceneDataUrl; }
    public void setSceneDataUrl(String sceneDataUrl) { this.sceneDataUrl = sceneDataUrl; }
}
