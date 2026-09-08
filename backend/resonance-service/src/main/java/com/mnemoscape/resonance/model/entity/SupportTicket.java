package com.mnemoscape.resonance.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 客服工单。
 *
 * <p>用户提交问题（subject + description）创建一个 ticket，管理员（客服角色）可以
 * 在工单内与用户实时聊天（消息存在 {@link SupportMessage}）。状态机：OPEN → IN_PROGRESS
 * → RESOLVED → CLOSED。
 */
@Entity
@Table(name = "support_tickets", indexes = {
        @Index(name = "idx_support_user", columnList = "user_id"),
        @Index(name = "idx_support_status", columnList = "status"),
        @Index(name = "idx_support_assigned", columnList = "assigned_admin_id"),
        @Index(name = "idx_support_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class SupportTicket {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** OPEN | IN_PROGRESS | RESOLVED | CLOSED */
    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    /** LOW | NORMAL | HIGH | URGENT */
    @Column(nullable = false, length = 16)
    private String priority = "NORMAL";

    /** 指派给的管理员 / 客服 ID（首次回复时由后端自动填充）。 */
    @Column(name = "assigned_admin_id", length = 36)
    private String assignedAdminId;

    /** 客户端类别（VUE_WEB / IOS / ANDROID 等），可选元数据。 */
    @Column(name = "client_type", length = 32)
    private String clientType;

    /** 用户最后一次活动时间，便于按"最近回复"排序。 */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (lastMessageAt == null) lastMessageAt = createdAt;
        if (id == null) id = UUID.randomUUID().toString();
        if (status == null || status.isBlank()) status = "OPEN";
        if (priority == null || priority.isBlank()) priority = "NORMAL";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
