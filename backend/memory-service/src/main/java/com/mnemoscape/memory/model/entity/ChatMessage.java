package com.mnemoscape.memory.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 聊天记忆实体 (ChatMessage)。
 * 用于记录用户对话上下文，并支持热 (Redis) / 温 (MySQL) / 冷 (MinIO) 三层分层存储。
 */
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_msg_user_session", columnList = "user_id,session_id"),
        @Index(name = "idx_chat_msg_archived_created", columnList = "archived,created_at"),
        @Index(name = "idx_chat_msg_user_archived", columnList = "user_id,archived")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "role", nullable = false, length = 20)
    private String role; // USER, ASSISTANT, SYSTEM

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Builder.Default
    @Column(name = "message_type", length = 20)
    private String messageType = "TEXT";

    @Builder.Default
    @Column(name = "archived", nullable = false)
    private Boolean archived = false;

    @Column(name = "archive_key", length = 255)
    private String archiveKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public ChatMessage(String userId, String sessionId, String role, String content) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.messageType = "TEXT";
        this.archived = false;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.archived == null) {
            this.archived = false;
        }
        if (this.messageType == null || this.messageType.isBlank()) {
            this.messageType = "TEXT";
        }
    }
}
