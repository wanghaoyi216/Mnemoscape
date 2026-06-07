package com.mnemoscape.memory.messaging.outbox;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 事务性 Outbox 表 —— 把"业务写库 + 发 MQ"两步合并为"业务写库 + 写 outbox 同行"。
 *
 * <p><b>模式</b>：标准的 Transactional Outbox Pattern。业务方法在 {@code @Transactional}
 * 范围内完成两件事：① 写业务表（Memory 等）② 写本表。事务提交时两者一起持久化，
 * 不会出现"DB 写成功 + MQ 消息丢失"的不一致。然后独立线程（{@link OutboxRetryScheduler}）
 * 每 N 秒扫表，把 PENDING 行推到 broker，成功后标记 PUBLISHED 并删除。
 *
 * <p><b>为什么不用 EventPublisher 的失败兜底</b>：失败兜底写 outbox 解决了
 * "MQ 临时不可用"的场景，但无法解决"业务事务提交 + MQ 发送"两个动作之间的崩溃
 * （JVM 突然挂掉 / 容器 OOM kill / 网络分区）—— 业务数据已落库，但 MQ 没消息。
 * 真正可靠的方案是把消息持久化与业务持久化放在同一事务里，本表承担这个角色。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code eventId} —— UUID，全局唯一。消费端幂等键。</li>
 *   <li>{@code routingKey} —— RabbitMQ routing key（含 domain.entity.action）。</li>
 *   <li>{@code payloadType} —— payload 全限定类名。读时按此反序列化（不依赖 polymorphic typing）。</li>
 *   <li>{@code payload} —— Jackson 序列化后的 JSON 字符串。</li>
 *   <li>{@code attempts} —— 已重试次数。指数退避 5s, 10s, 20s, ..., 320s。</li>
 *   <li>{@code nextAttemptAt} —— 下次可被扫到的最早时间。</li>
 *   <li>{@code status} —— PENDING / PUBLISHED / FAILED。PUBLISHED 立即删，FAILED 留作审计。</li>
 *   <li>{@code lastError} —— 最后一次失败原因（截断到 500 字符），运维查错用。</li>
 * </ul>
 */
@Entity
@Table(name = "outbox_event",
        indexes = {
                @Index(name = "idx_outbox_status_next", columnList = "status,next_attempt_at"),
                @Index(name = "uk_outbox_event_id", columnList = "event_id", unique = true)
        })
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64, unique = true)
    private String eventId;

    @Column(name = "routing_key", nullable = false, length = 128)
    private String routingKey;

    @Column(name = "payload_type", nullable = false, length = 256)
    private String payloadType;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }
    public String getPayloadType() { return payloadType; }
    public void setPayloadType(String payloadType) { this.payloadType = payloadType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
