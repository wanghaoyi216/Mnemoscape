package com.mnemoscape.auth.model.dto;

import java.time.LocalDateTime;

/**
 * 用户 3D 刻画响应 DTO。
 *
 * <p>包含 AI 生成的角色特征数据，供前端 Three.js 渲染器直接消费。
 */
public class AvatarProfileResponse {

    private String id;
    private String userId;
    private String selfDescription;

    /**
     * AI 解析出的 3D 角色特征 JSON 字符串。
     * 前端直接 JSON.parse 后传给 Three.js 渲染器。
     * 结构：{ coreColor, auraColor, particleType, particleCount, coreShape,
     *         auraIntensity, rotationSpeed, glowRadius, trailEffect }
     */
    private String avatarTraits;

    /**
     * 角色的情绪基调 JSON 字符串。
     * 结构：{ warmth, energy, depth, brightness, mystique }
     */
    private String emotionTone;

    /**
     * 角色的个性标签 JSON 数组字符串。
     * 例如：["内敛", "夜行者", "星空爱好者"]
     */
    private String personalityTags;

    /** AI 生成的角色诗意名号。 */
    private String avatarTitle;

    /** AI 生成的角色背景故事。 */
    private String avatarStory;

    /** 是否公开展示。 */
    private Boolean isPublic;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AvatarProfileResponse() {}

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
