package com.mnemoscape.resonance.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_groups")
@Getter
@Setter
@NoArgsConstructor
public class ChatGroup {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public ChatGroup(String id, String name, String avatarUrl, String ownerId, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }
}
