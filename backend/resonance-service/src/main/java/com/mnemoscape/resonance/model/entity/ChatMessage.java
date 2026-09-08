package com.mnemoscape.resonance.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_msg_sender", columnList = "sender_id"),
        @Index(name = "idx_chat_msg_receiver", columnList = "receiver_id"),
        @Index(name = "idx_chat_msg_group", columnList = "group_id"),
        @Index(name = "idx_chat_msg_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "sender_id", nullable = false, length = 36)
    private String senderId;

    @Column(name = "receiver_id", length = 36)
    private String receiverId;

    @Column(name = "group_id", length = 36)
    private String groupId;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "message_type", length = 20)
    private String messageType = "TEXT";

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public ChatMessage(String id, String senderId, String receiverId, String groupId, String content, String messageType, String fileName, Long fileSize, LocalDateTime createdAt) {
        this.id = id;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.groupId = groupId;
        this.content = content;
        this.messageType = messageType;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.createdAt = createdAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }
}
