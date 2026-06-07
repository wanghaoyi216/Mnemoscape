package com.mnemoscape.memory.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "drift_bottles")
public class DriftBottle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String memoryId;

    @Column(nullable = false, length = 500)
    private String snippet;

    @Column
    private String emotion;

    @Column
    private String location;

    @Column
    private Integer year;

    @Column
    private String pickedByUserId;

    @Column
    private LocalDateTime pickedAt;

    @Column(nullable = false)
    private LocalDateTime thrownAt;

    @Column(nullable = false)
    private Boolean isActive = true;

    public DriftBottle() {}

    public DriftBottle(String userId, String memoryId, String snippet) {
        this.userId = userId;
        this.memoryId = memoryId;
        this.snippet = snippet;
        this.thrownAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
    public String getEmotion() { return emotion; }
    public void setEmotion(String emotion) { this.emotion = emotion; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public String getPickedByUserId() { return pickedByUserId; }
    public void setPickedByUserId(String pickedByUserId) { this.pickedByUserId = pickedByUserId; }
    public LocalDateTime getPickedAt() { return pickedAt; }
    public void setPickedAt(LocalDateTime pickedAt) { this.pickedAt = pickedAt; }
    public LocalDateTime getThrownAt() { return thrownAt; }
    public void setThrownAt(LocalDateTime thrownAt) { this.thrownAt = thrownAt; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
