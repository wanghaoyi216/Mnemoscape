package com.mnemoscape.auth.model.dto;

public class AuthResponse {
    private String userId;
    private String username;
    private String accessToken;
    private String refreshToken;
    private long expiresIn;
    /** 当前登录用户的角色，取值集合 {@code {"USER","ADMIN"}}。供前端 store 写入并驱动 admin 路由守卫。 */
    private String role;

    public AuthResponse() {
    }

    public AuthResponse(String userId, String username, String accessToken, String refreshToken, long expiresIn) {
        this(userId, username, accessToken, refreshToken, expiresIn, "USER");
    }

    public AuthResponse(String userId, String username, String accessToken, String refreshToken, long expiresIn, String role) {
        this.userId = userId;
        this.username = username;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
        this.role = (role == null || role.isBlank()) ? "USER" : role;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public String getRole() {
        return role == null ? "USER" : role;
    }

    public void setRole(String role) {
        this.role = (role == null || role.isBlank()) ? "USER" : role;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String userId;
        private String username;
        private String accessToken;
        private String refreshToken;
        private long expiresIn;
        private String role = "USER";

        private Builder() {
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder refreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
            return this;
        }

        public Builder expiresIn(long expiresIn) {
            this.expiresIn = expiresIn;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public AuthResponse build() {
            return new AuthResponse(userId, username, accessToken, refreshToken, expiresIn, role);
        }
    }
}
