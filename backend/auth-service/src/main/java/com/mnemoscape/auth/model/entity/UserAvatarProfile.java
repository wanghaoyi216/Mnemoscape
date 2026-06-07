package com.mnemoscape.auth.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户 3D 刻画档案。
 *
 * <p>存储用户通过自然语言描述生成的 3D 角色形象数据，包括：
 * <ul>
 *   <li>用户的自我描述文本（{@code selfDescription}）</li>
 *   <li>AI 解析出的角色特征 JSON（{@code avatarTraits}）：颜色/形状/粒子/光晕等</li>
 *   <li>角色的情绪基调 JSON（{@code emotionTone}）：影响 3D 渲染的色温与粒子行为</li>
 *   <li>角色的个性标签 JSON（{@code personalityTags}）：用于共鸣匹配</li>
 * </ul>
 *
 * <p>每个用户只有一条记录（one-to-one with User），通过 {@code userId} 唯一关联。
 * 使用 JPA {@code ddl-auto: update} 自动建表，无需手写 SQL。
 */
@Entity
@Table(name = "user_avatar_profiles", indexes = {
        @Index(name = "idx_uap_user_id", columnList = "user_id", unique = true)
})
public class UserAvatarProfile {

    @Id
    @Column(length = 36)
    private String id;

    /** 关联的用户 ID（唯一，one-to-one 语义）。 */
    @Column(name = "user_id", nullable = false, unique = true, length = 36)
    private String userId;

    /**
     * 用户的自我描述文本（自然语言）。
     * 例如："我是一个安静的人，喜欢在夜晚读书，内心有一片星空。"
     */
    @Column(name = "self_description", columnDefinition = "TEXT")
    private String selfDescription;

    /**
     * AI 解析出的 3D 角色特征 JSON。
     * 结构示例：
     * <pre>
     * {
     *   "coreColor": "#6c63ff",
     *   "auraColor": "#36d8b4",
     *   "particleType": "stars",   // stars / fireflies / snowflakes / petals / embers
     *   "particleCount": 1500,
     *   "coreShape": "sphere",     // sphere / crystal / nebula / prism
     *   "auraIntensity": 0.75,
     *   "rotationSpeed": 0.4,
     *   "glowRadius": 2.5,
     *   "trailEffect": true
     * }
     * </pre>
     */
    @Column(name = "avatar_traits", columnDefinition = "JSON")
    private String avatarTraits;

    /**
     * 角色的情绪基调 JSON（影响 3D 渲染的色温与粒子行为）。
     * 结构示例：
     * <pre>
     * {
     *   "warmth": 0.6,       // 暖色调倾向 0-1
     *   "energy": 0.4,       // 活跃度 0-1
     *   "depth": 0.8,        // 深沉感 0-1
     *   "brightness": 0.55,  // 明亮度 0-1
     *   "mystique": 0.7      // 神秘感 0-1
     * }
     * </pre>
     */
    @Column(name = "emotion_tone", columnDefinition = "JSON")
    private String emotionTone;

    /**
     * 角色的个性标签 JSON 数组（用于共鸣匹配与展示）。
     * 例如：["内敛", "夜行者", "星空爱好者", "读书人"]
     */
    @Column(name = "personality_tags", columnDefinition = "JSON")
    private String personalityTags;

    /**
     * 角色的诗意名号（AI 生成的一句话角色定义）。
     * 例如："夜色中独行的星图收藏者"
     */
    @Column(name = "avatar_title", length = 100)
    private String avatarTitle;

    /**
     * 角色的背景故事（AI 生成的简短叙述，用于共鸣空间展示）。
     * 例如："在无数个深夜，他/她把星空的碎片一片片收进记忆的博物馆。"
     */
    @Column(name = "avatar_story", columnDefinition = "TEXT")
    private String avatarStory;

    /** 是否公开展示（公开后可在共鸣空间被其他用户看到）。 */
    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserAvatarProfile() {}

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (id == null) id = java.util.UUID.randomUUID().toString();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSelfDescription() { return selfDescription; }
    public void setSelfDescription(String selfDescription) { this.selfDescription = selfDescription; }

    public String getAvatarTraits() { return avatarTraits; }
    public void setAvatarTraits(String avatarTraits) { this.avatarTraits = avatarTraits; }

    public String getEmotionTone() { return emotionTone; }
    public void setEmotionTone(String emotionTone) { this.emotionTone = emotionTone; }

    public String getPersonalityTags() { return personalityTags; }
    public void setPersonalityTags(String personalityTags) { this.personalityTags = personalityTags; }

    public String getAvatarTitle() { return avatarTitle; }
    public void setAvatarTitle(String avatarTitle) { this.avatarTitle = avatarTitle; }

    public String getAvatarStory() { return avatarStory; }
    public void setAvatarStory(String avatarStory) { this.avatarStory = avatarStory; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
