package com.mnemoscape.resonance.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 客服工单内的对话消息。
 *
 * <p>每条 ticket 对应多条 message，sender 可以是用户（USER）或管理员（ADMIN）。
 * messageType 支持 TEXT / IMAGE / FILE，对应 content 字段语义：
 *   - TEXT: content 为文本（含 emoji，Unicode）
 *   - IMAGE: content 为图片 URL（一般来自 asset-service 上传）
 *   - FILE: content 为文件 URL，附加 fileName / fileSize
 */
@Entity
@Table(name = "support_messages", indexes = {
        @Index(name = "idx_support_msg_ticket", columnList = "ticket_id"),
        @Index(name = "idx_support_msg_sender", columnList = "sender_id"),
        @Index(name = "idx_support_msg_created", columnList = "created_at")
})
public class SupportMessage {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "ticket_id", nullable = false, length = 36)
    private String ticketId;

    @Column(name = "sender_id", nullable = false, length = 36)
    private String senderId;

    /** USER | ADMIN — 谁发的。前端按这个字段决定气泡左右与样式。 */
    @Column(name = "sender_role", nullable = false, length = 16)
    private String senderRole;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** TEXT | IMAGE | FILE | EMOJI | SYSTEM */
    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType = "TEXT";

    @Column(name = "file_name", length = 200)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    /** 已读时间（用户已读 / 管理员已读视图的 last seen 推动）。 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public SupportMessage() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String senderRole) { this.senderRole = senderRole; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
        if (messageType == null || messageType.isBlank()) messageType = "TEXT";
    }
}
