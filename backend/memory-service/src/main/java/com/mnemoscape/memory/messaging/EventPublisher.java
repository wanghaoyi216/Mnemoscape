package com.mnemoscape.memory.messaging;

import com.mnemoscape.common.constant.ServiceConstants;
import com.mnemoscape.common.event.AchievementUnlockedEvent;
import com.mnemoscape.common.event.DriftBottleEvent;
import com.mnemoscape.common.event.MemoryDeletedEvent;
import com.mnemoscape.common.event.MemoryIndexedEvent;
import com.mnemoscape.memory.messaging.outbox.OutboxEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * memory-service 的领域事件发布门面。
 *
 * <p>所有 publish 方法都遵循"失败可控"原则：
 * <ul>
 *   <li>返回 boolean —— true = broker 已收下，false = broker 不可达 / 序列化失败。</li>
 *   <li>不抛异常 —— 调用方主流程（创建/更新/删除记忆）必须无感继续。</li>
 *   <li>调用方拿到 false 时应走 Feign 兜底（同步调 ai-service），保证向量索引 /
 *       图谱写入最终能执行。RabbitMQ 一恢复就回归异步主路径。</li>
 * </ul>
 *
 * <p><b>可靠性分层</b>（自上而下依次兜底）：
 * <ol>
 *   <li>RabbitMQ 直发 —— 99% 情况，几毫秒延迟、零业务阻塞。</li>
 *   <li>Feign 同步调用（由 MemoryService 在 MQ 失败时触发）—— 立即语义，
 *       处理 broker 抖动。</li>
 *   <li><b>Outbox 持久化</b>（本类 fallback）—— 当 1+2 都失败时把事件落 outbox 表，
 *       {@code OutboxRetryScheduler} 每 30s 扫表重发。这层保证 broker 长时间
 *       不可用 / 进程崩溃时事件不丢。</li>
 * </ol>
 *
 * <p><b>幂等保证</b>：所有事件都自带 memoryId（或 bottleId / achievement code），
 * Consumer 端把它作为 upsert key，重复消费不会产生重复行。
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final OutboxEventPublisher outboxPublisher;

    public EventPublisher(RabbitTemplate mnemoscapeRabbitTemplate,
                            OutboxEventPublisher outboxPublisher) {
        this.rabbitTemplate = mnemoscapeRabbitTemplate;
        this.outboxPublisher = outboxPublisher;
    }

    /** 记忆已落库 → 触发 ai-service 异步索引向量 + Neo4j 图谱写入。 */
    public boolean publishMemoryIndexed(MemoryIndexedEvent event) {
        return safePublish(ServiceConstants.RK_MEMORY_INDEXED, event,
                "memory.indexed memoryId=" + event.memoryId() + " action=" + event.action());
    }

    /** 记忆已删除 → 扇出给 ai-service 清向量 + resonance-service 失效缓存。 */
    public boolean publishMemoryDeleted(MemoryDeletedEvent event) {
        return safePublish(ServiceConstants.RK_MEMORY_DELETED, event,
                "memory.deleted memoryId=" + event.memoryId());
    }

    /** 漂流瓶投放 / 捞起 —— routing key 自动按 event.action 拼接。 */
    public boolean publishDriftBottle(DriftBottleEvent event) {
        String routingKey = "thrown".equals(event.action())
                ? ServiceConstants.RK_DRIFTBOTTLE_THROWN
                : ServiceConstants.RK_DRIFTBOTTLE_PICKED;
        return safePublish(routingKey, event,
                routingKey + " bottleId=" + event.bottleId());
    }

    /** 成就解锁 → 推送通知 + 失效共鸣大厅 top 缓存。 */
    public boolean publishAchievementUnlocked(AchievementUnlockedEvent event) {
        return safePublish(ServiceConstants.RK_ACHIEVEMENT_UNLOCKED, event,
                "achievement.unlocked userId=" + event.userId() + " code=" + event.achievementCode());
    }

    private boolean safePublish(String routingKey, Object payload, String desc) {
        try {
            rabbitTemplate.convertAndSend(
                    ServiceConstants.EVENTS_EXCHANGE,
                    routingKey,
                    payload);
            log.debug("[mq-out] {} → {}/{}", desc,
                    ServiceConstants.EVENTS_EXCHANGE, routingKey);
            return true;
        } catch (AmqpException e) {
            // broker 不可达 / 序列化错 / channel 关闭 — 落 outbox 让 worker 兜底
            log.warn("[mq-out] FAILED to publish {}: {} (staging to outbox)", desc, e.getMessage());
            stageToOutbox(routingKey, payload, desc);
            return false;
        } catch (Exception e) {
            log.warn("[mq-out] UNEXPECTED error publishing {}: {} (staging to outbox)", desc, e.toString());
            stageToOutbox(routingKey, payload, desc);
            return false;
        }
    }

    private void stageToOutbox(String routingKey, Object payload, String desc) {
        try {
            String eventId = outboxPublisher.stageBestEffort(routingKey, payload);
            if (eventId != null) {
                log.info("[outbox-fallback] staged eventId={} for {} (routingKey={})",
                        eventId, desc, routingKey);
            }
        } catch (Exception e) {
            // Outbox 写入失败 —— 此时最坏情况：业务事务已提交 + MQ 消息未发出 +
            // outbox 落库失败 = 事件丢失。需要监控告警（FAILED count + 5min 静默期）。
            log.error("[outbox-fallback] CRITICAL: outbox write failed for {}: {}", desc, e.toString());
        }
    }
}
