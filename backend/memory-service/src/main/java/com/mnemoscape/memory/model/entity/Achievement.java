package com.mnemoscape.memory.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_achievements")
public class Achievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String achievementKey;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Column
    private String icon;

    @Column(nullable = false)
    private LocalDateTime unlockedAt;

    public Achievement() {}

    public Achievement(String userId, String achievementKey, String title, String description, String icon) {
        this.userId = userId;
        this.achievementKey = achievementKey;
        this.title = title;
        this.description = description;
        this.icon = icon;
        this.unlockedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAchievementKey() { return achievementKey; }
    public void setAchievementKey(String achievementKey) { this.achievementKey = achievementKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public LocalDateTime getUnlockedAt() { return unlockedAt; }
    public void setUnlockedAt(LocalDateTime unlockedAt) { this.unlockedAt = unlockedAt; }
}
