package com.mnemoscape.memory.model.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import com.mnemoscape.memory.model.converter.MemoryPrivacyLevelConverter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "memories", indexes = {
        @Index(name = "idx_memories_user_id", columnList = "user_id"),
        @Index(name = "idx_memories_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_memories_privacy", columnList = "privacy_level"),
        @Index(name = "idx_memories_fade_level", columnList = "fade_level"),
        @Index(name = "idx_memories_is_locked", columnList = "is_locked")
})
@Getter
@Setter
@NoArgsConstructor
public class Memory {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "memory_year")
    private Integer memoryYear;

    @Column(name = "memory_date")
    private LocalDate memoryDate;

    @Column(name = "memory_season", length = 10)
    private String memorySeason;

    @Column(name = "memory_time_of_day", length = 10)
    private String memoryTimeOfDay;

    @Column(name = "memory_location", length = 255)
    private String memoryLocation;

    /** 经度（east+ / west-）。由 GeocodingService 解析或用户上传 */
    @Column(name = "memory_lng")
    private Double memoryLng;

    /** 纬度（north+ / south-）。由 GeocodingService 解析或用户上传 */
    @Column(name = "memory_lat")
    private Double memoryLat;

    @Convert(converter = MemoryPrivacyLevelConverter.class)
    @Column(name = "privacy_level", nullable = false)
    private PrivacyLevel privacyLevel = PrivacyLevel.PRIVATE;

    @Column(name = "is_locked", nullable = false)
    private Boolean isLocked = false;

    @Column(name = "fade_level")
    private Double fadeLevel = 0.0;

    @Column(name = "last_drift_calculated_at")
    private LocalDateTime lastDriftCalculatedAt;

    @Column(name = "scene_data_url", length = 500)
    private String sceneDataUrl;

    @Column(name = "emotion_vector_id", length = 100)
    private String emotionVectorId;

    @Column(name = "visual_data", columnDefinition = "JSON")
    private String visualData;

    @Column(name = "audio_data", columnDefinition = "JSON")
    private String audioData;

    @Column(name = "emotion_profile", columnDefinition = "JSON")
    private String emotionProfile;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum PrivacyLevel { PRIVATE, FRIENDS, PUBLIC }

    public Memory(String id, String userId, String title, String description, Integer memoryYear, LocalDate memoryDate, String memorySeason, String memoryTimeOfDay, String memoryLocation, Double memoryLng, Double memoryLat, PrivacyLevel privacyLevel, Boolean isLocked, Double fadeLevel, LocalDateTime lastDriftCalculatedAt, String sceneDataUrl, String emotionVectorId, String visualData, String audioData, String emotionProfile, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.description = description;
        this.memoryYear = memoryYear;
        this.memoryDate = memoryDate;
        this.memorySeason = memorySeason;
        this.memoryTimeOfDay = memoryTimeOfDay;
        this.memoryLocation = memoryLocation;
        this.memoryLng = memoryLng;
        this.memoryLat = memoryLat;
        this.privacyLevel = privacyLevel;
        this.isLocked = isLocked;
        this.fadeLevel = fadeLevel;
        this.lastDriftCalculatedAt = lastDriftCalculatedAt;
        this.sceneDataUrl = sceneDataUrl;
        this.emotionVectorId = emotionVectorId;
        this.visualData = visualData;
        this.audioData = audioData;
        this.emotionProfile = emotionProfile;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static MemoryBuilder builder() {
        return new MemoryBuilder();
    }

    public static class MemoryBuilder {
        private String id;
        private String userId;
        private String title;
        private String description;
        private Integer memoryYear;
        private LocalDate memoryDate;
        private String memorySeason;
        private String memoryTimeOfDay;
        private String memoryLocation;
        private Double memoryLng;
        private Double memoryLat;
        private PrivacyLevel privacyLevel = PrivacyLevel.PRIVATE;
        private Boolean isLocked = false;
        private Double fadeLevel = 0.0;
        private LocalDateTime lastDriftCalculatedAt;
        private String sceneDataUrl;
        private String emotionVectorId;
        private String visualData;
        private String audioData;
        private String emotionProfile;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        MemoryBuilder() {}

        public MemoryBuilder id(String id) { this.id = id; return this; }
        public MemoryBuilder userId(String userId) { this.userId = userId; return this; }
        public MemoryBuilder title(String title) { this.title = title; return this; }
        public MemoryBuilder description(String description) { this.description = description; return this; }
        public MemoryBuilder memoryYear(Integer memoryYear) { this.memoryYear = memoryYear; return this; }
        public MemoryBuilder memoryDate(LocalDate memoryDate) { this.memoryDate = memoryDate; return this; }
        public MemoryBuilder memorySeason(String memorySeason) { this.memorySeason = memorySeason; return this; }
        public MemoryBuilder memoryTimeOfDay(String memoryTimeOfDay) { this.memoryTimeOfDay = memoryTimeOfDay; return this; }
        public MemoryBuilder memoryLocation(String memoryLocation) { this.memoryLocation = memoryLocation; return this; }
        public MemoryBuilder memoryLng(Double memoryLng) { this.memoryLng = memoryLng; return this; }
        public MemoryBuilder memoryLat(Double memoryLat) { this.memoryLat = memoryLat; return this; }
        public MemoryBuilder privacyLevel(PrivacyLevel privacyLevel) { this.privacyLevel = privacyLevel; return this; }
        public MemoryBuilder isLocked(Boolean isLocked) { this.isLocked = isLocked; return this; }
        public MemoryBuilder fadeLevel(Double fadeLevel) { this.fadeLevel = fadeLevel; return this; }
        public MemoryBuilder lastDriftCalculatedAt(LocalDateTime lastDriftCalculatedAt) { this.lastDriftCalculatedAt = lastDriftCalculatedAt; return this; }
        public MemoryBuilder sceneDataUrl(String sceneDataUrl) { this.sceneDataUrl = sceneDataUrl; return this; }
        public MemoryBuilder emotionVectorId(String emotionVectorId) { this.emotionVectorId = emotionVectorId; return this; }
        public MemoryBuilder visualData(String visualData) { this.visualData = visualData; return this; }
        public MemoryBuilder audioData(String audioData) { this.audioData = audioData; return this; }
        public MemoryBuilder emotionProfile(String emotionProfile) { this.emotionProfile = emotionProfile; return this; }
        public MemoryBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public MemoryBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public Memory build() {
            return new Memory(id, userId, title, description, memoryYear, memoryDate, memorySeason, memoryTimeOfDay, memoryLocation, memoryLng, memoryLat, privacyLevel, isLocked, fadeLevel, lastDriftCalculatedAt, sceneDataUrl, emotionVectorId, visualData, audioData, emotionProfile, createdAt, updatedAt);
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
