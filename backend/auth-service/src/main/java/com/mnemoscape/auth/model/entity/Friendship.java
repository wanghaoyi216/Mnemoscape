package com.mnemoscape.auth.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "friendships", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id_1", "user_id_2"})
})
@Getter
@Setter
@NoArgsConstructor
public class Friendship {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id_1", nullable = false, length = 36)
    private String userId1;

    @Column(name = "user_id_2", nullable = false, length = 36)
    private String userId2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FriendshipStatus status = FriendshipStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum FriendshipStatus { PENDING, ACCEPTED, REJECTED }

    public Friendship(String id, String userId1, String userId2, FriendshipStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId1 = userId1;
        this.userId2 = userId2;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String userId1;
        private String userId2;
        private FriendshipStatus status = FriendshipStatus.PENDING;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder userId1(String userId1) {
            this.userId1 = userId1;
            return this;
        }

        public Builder userId2(String userId2) {
            this.userId2 = userId2;
            return this;
        }

        public Builder status(FriendshipStatus status) {
            this.status = status;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Friendship build() {
            return new Friendship(id, userId1, userId2, status, createdAt, updatedAt);
        }
    }

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
}
