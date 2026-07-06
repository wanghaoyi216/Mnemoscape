package com.mnemoscape.auth.model.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UserProfileResponse {
    private String id;
    private String username;
    private String email;
    private String avatarUrl;
    private String backgroundImageUrl;
    /** 用户角色，取值集合 {@code {"USER","ADMIN"}}。供前端 fetchProfile 写入 auth store。 */
    private String role;
    private Boolean verified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UserProfileResponse(String id, String username, String email, String avatarUrl, Boolean verified, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, username, email, avatarUrl, null, "USER", verified, createdAt, updatedAt);
    }

    public UserProfileResponse(String id, String username, String email, String avatarUrl, String backgroundImageUrl, Boolean verified, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, username, email, avatarUrl, backgroundImageUrl, "USER", verified, createdAt, updatedAt);
    }

    public UserProfileResponse(String id, String username, String email, String avatarUrl, String backgroundImageUrl, String role, Boolean verified, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.avatarUrl = avatarUrl;
        this.backgroundImageUrl = backgroundImageUrl;
        this.role = (role == null || role.isBlank()) ? "USER" : role;
        this.verified = verified;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getRole() {
        return role == null ? "USER" : role;
    }

    public void setRole(String role) {
        this.role = (role == null || role.isBlank()) ? "USER" : role;
    }
}
