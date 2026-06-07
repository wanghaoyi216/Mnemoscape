package com.mnemoscape.resonance.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_msg_sender", columnList = "sender_id"),
        @Index(name = "idx_chat_msg_receiver", columnList = "receiver_id"),
        @Index(name = "idx_chat_msg_group", columnList = "group_id"),
        @Index(name = "idx_chat_msg_created", columnList = "created_at")
})
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

    public ChatMessage() {}

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getReceiverId() { return receiverId; }
    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }
}
