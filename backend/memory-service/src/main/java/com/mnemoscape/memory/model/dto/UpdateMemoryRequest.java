package com.mnemoscape.memory.model.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateMemoryRequest {
    @Size(max = 200)
    private String title;

    @Size(min = 5, message = "Memory description must be at least 5 characters")
    private String description;

    private Integer memoryYear;
    private String memoryDate;
    private String memorySeason;
    private String memoryTimeOfDay;
    private String memoryLocation;

    @Pattern(regexp = "(?i)PRIVATE|FRIENDS|PUBLIC", message = "Privacy level must be PRIVATE, FRIENDS, or PUBLIC")
    private String privacyLevel;

    private String sceneDataUrl;

    public UpdateMemoryRequest() {}

    public UpdateMemoryRequest(String title, String description, Integer memoryYear, String memoryDate, String memorySeason,
                               String memoryTimeOfDay, String memoryLocation, String privacyLevel, String sceneDataUrl) {
        this.title = title;
        this.description = description;
        this.memoryYear = memoryYear;
        this.memoryDate = memoryDate;
        this.memorySeason = memorySeason;
        this.memoryTimeOfDay = memoryTimeOfDay;
        this.memoryLocation = memoryLocation;
        this.privacyLevel = privacyLevel;
        this.sceneDataUrl = sceneDataUrl;
    }

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
    public String getPrivacyLevel() { return privacyLevel; }
    public void setPrivacyLevel(String privacyLevel) { this.privacyLevel = privacyLevel; }
    public String getSceneDataUrl() { return sceneDataUrl; }
    public void setSceneDataUrl(String sceneDataUrl) { this.sceneDataUrl = sceneDataUrl; }
}
