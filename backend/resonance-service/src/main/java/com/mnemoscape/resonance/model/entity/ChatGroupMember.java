package com.mnemoscape.resonance.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_group_members", uniqueConstraints = {
        @UniqueConstraint(name = "uk_group_member", columnNames = {"group_id", "user_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class ChatGroupMember {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "group_id", nullable = false, length = 36)
    private String groupId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "joined_at", updatable = false)
    private LocalDateTime joinedAt;

    public ChatGroupMember(String id, String groupId, String userId, LocalDateTime joinedAt) {
        this.id = id;
        this.groupId = groupId;
        this.userId = userId;
        this.joinedAt = joinedAt;
    }

    @PrePersist
    protected void onCreate() {
        joinedAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }
}
