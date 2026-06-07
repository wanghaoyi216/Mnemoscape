-- =============================================================================
-- Migration: support-tickets.sql
-- Feature: 客服问答系统 (customer support tickets + messages)
-- Target: MySQL 8.0.16+
-- Database: resonance-service shared database
--
-- 运维须知:
--   项目尚未引入 Flyway / Liquibase。该脚本【必须在部署客服功能前手工执行】。
--   Hibernate ddl-auto=update 会自动为新增 @Entity 创建表，但不会创建
--   外键 / CHECK 约束 / 索引以外的复合索引；该脚本补齐这些约束。
--
--   执行命令示例:
--     mysql -h <host> -u <user> -p <resonance_db> < support-tickets.sql
-- =============================================================================

-- 1) 工单主表
CREATE TABLE IF NOT EXISTS support_tickets (
    id                  VARCHAR(36)  NOT NULL,
    user_id             VARCHAR(36)  NOT NULL,
    subject             VARCHAR(200) NOT NULL,
    description         TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    priority            VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    assigned_admin_id   VARCHAR(36),
    client_type         VARCHAR(32),
    last_message_at     DATETIME,
    created_at          DATETIME,
    updated_at          DATETIME,
    PRIMARY KEY (id),
    KEY idx_support_user      (user_id),
    KEY idx_support_status    (status),
    KEY idx_support_assigned  (assigned_admin_id),
    KEY idx_support_created   (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- CHECK 约束限定状态 / 优先级
ALTER TABLE support_tickets
    ADD CONSTRAINT chk_support_status
    CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'));
ALTER TABLE support_tickets
    ADD CONSTRAINT chk_support_priority
    CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT'));

-- 2) 消息表
CREATE TABLE IF NOT EXISTS support_messages (
    id            VARCHAR(36)  NOT NULL,
    ticket_id     VARCHAR(36)  NOT NULL,
    sender_id     VARCHAR(36)  NOT NULL,
    sender_role   VARCHAR(16)  NOT NULL,
    content       TEXT,
    message_type  VARCHAR(20)  NOT NULL DEFAULT 'TEXT',
    file_name     VARCHAR(200),
    file_size     BIGINT,
    read_at       DATETIME,
    created_at    DATETIME,
    PRIMARY KEY (id),
    KEY idx_support_msg_ticket  (ticket_id),
    KEY idx_support_msg_sender  (sender_id),
    KEY idx_support_msg_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE support_messages
    ADD CONSTRAINT chk_support_msg_role
    CHECK (sender_role IN ('USER', 'ADMIN', 'SYSTEM'));
ALTER TABLE support_messages
    ADD CONSTRAINT chk_support_msg_type
    CHECK (message_type IN ('TEXT', 'IMAGE', 'FILE', 'EMOJI', 'SYSTEM'));
