package com.mnemoscape.resonance.messaging;

import com.mnemoscape.common.event.AchievementUnlockedEvent;
import com.mnemoscape.common.event.DriftBottleEvent;
import com.mnemoscape.common.event.MemoryDeletedEvent;
import com.mnemoscape.common.idempotency.MessageDedupGuard;
import com.mnemoscape.resonance.admin.config.AdminCacheConfig;
import com.mnemoscape.resonance.model.entity.ChatMessage;
import com.mnemoscape.resonance.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * resonance-service 端 Consumer。
 *
 * <p>所有 handler 显式吞掉异常：业务失败不应让 listener 容器持续 requeue
 * 同一消息（已经定义 DLX 兜底），业务侧降级要"宁可不通知也不要给 broker 制造风暴"。
 */
@Component
public class DomainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);

    private final ChatMessageRepository chatMessageRepository;
    private final CacheManager cacheManager;
    private final MessageDedupGuard dedupGuard;

    public DomainEventConsumer(ChatMessageRepository chatMessageRepository,
                               CacheManager cacheManager,
                               MessageDedupGuard dedupGuard) {
        this.chatMessageRepository = chatMessageRepository;
        this.cacheManager = cacheManager;
        this.dedupGuard = dedupGuard;
    }

    /** driftbottle.thrown / driftbottle.picked → 双方写系统通知 + 失效共鸣概览缓存。 */
    @RabbitListener(queues = "${mnemoscape.mq.queue.resonance-driftbottle:" +
            "resonance.driftbottle}", containerFactory = "rabbitListenerContainerFactory")
    public void onDriftBottle(DriftBottleEvent event) {
        if (event == null || event.bottleId() == null) {
            log.warn("[mq-in] driftbottle event null, skip");
            return;
        }
        var lease = dedupGuard.tryBegin("resonance.driftbottle", event.eventId(), driftBottleKey(event));
        if (lease.isEmpty()) {
            return;
        }
        try {
            invalidateOverview();
            // 双方系统消息：投放者与捡到者各一条
            if ("thrown".equals(event.action())) {
                writeSystemMessage(event.senderUserId(),
                        "您的记忆「" + safeSnippet(event.preview()) +
                                "」已投出，静静等待有缘人拾起吧。");
            } else if ("picked".equals(event.action())) {
                if (event.senderUserId() != null) {
                    writeSystemMessage(event.senderUserId(),
                            "您投出的漂流瓶被另一位用户拾起啦！");
                }
                if (event.pickerUserId() != null) {
                    writeSystemMessage(event.pickerUserId(),
                            "您拾起了一个漂流瓶：\"" + safeSnippet(event.preview()) + "\"");
                }
            }
            log.info("[mq-in] driftbottle action={} bottleId={} sender={} picker={}",
                    event.action(), event.bottleId(),
                    event.senderUserId(), event.pickerUserId());
            lease.get().markProcessed();
        } catch (Exception e) {
            lease.get().clear();
            log.warn("[mq-in] driftbottle handler failed for {}: {}",
                    event.bottleId(), e.toString());
        }
    }

    /** achievement.unlocked → 写通知 + 失效 admin top 缓存。 */
    @RabbitListener(queues = "${mnemoscape.mq.queue.resonance-achievement:" +
            "resonance.achievement}", containerFactory = "rabbitListenerContainerFactory")
    public void onAchievementUnlocked(AchievementUnlockedEvent event) {
        if (event == null || event.userId() == null || event.achievementCode() == null) {
            log.warn("[mq-in] achievement event null, skip");
            return;
        }
        var lease = dedupGuard.tryBegin("resonance.achievement", event.eventId(), achievementKey(event));
        if (lease.isEmpty()) {
            return;
        }
        try {
            writeSystemMessage(event.userId(),
                    "🎉 成就解锁：" + event.name() +
                            (event.triggeredByMemoryId() == null
                                    ? ""
                                    : "（源自您的一条记忆）"));
            invalidateTop();
            log.info("[mq-in] achievement userId={} code={}", event.userId(), event.achievementCode());
            lease.get().markProcessed();
        } catch (Exception e) {
            lease.get().clear();
            log.warn("[mq-in] achievement handler failed for {}: {}",
                    event.userId(), e.toString());
        }
    }

    /** memory.deleted → 共鸣大厅缓存失效（防"被删记忆"仍出现在 feed）。 */
    @RabbitListener(queues = "${mnemoscape.mq.queue.resonance-memory-evict:" +
            "resonance.memory.evict}", containerFactory = "rabbitListenerContainerFactory")
    public void onMemoryDeleted(MemoryDeletedEvent event) {
        if (event == null) return;
        var lease = dedupGuard.tryBegin("resonance.memory.deleted", event.eventId(), memoryDeleteKey(event));
        if (lease.isEmpty()) {
            return;
        }
        try {
            invalidateOverview();
            log.info("[mq-in] memory.deleted fed into resonance invalidation memoryId={} userId={}",
                    event.memoryId(), event.userId());
            lease.get().markProcessed();
        } catch (Exception e) {
            lease.get().clear();
            log.warn("[mq-in] memory.deleted handler failed for {}: {}", event.memoryId(), e.toString());
        }
    }

    @Transactional
    protected void writeSystemMessage(String receiverId, String content) {
        if (receiverId == null) return;
        try {
            ChatMessage msg = new ChatMessage(
                    UUID.randomUUID().toString(),
                    "SYSTEM",
                    receiverId,
                    null,
                    content,
                    "SYSTEM",
                    null,
                    null,
                    LocalDateTime.now());
            chatMessageRepository.save(msg);
        } catch (Exception e) {
            // DB 抖动也不抛 — listener retry 也救不了写库，让消息走完 ack
            log.warn("[mq-in] failed to persist system message for {}: {}", receiverId, e.toString());
        }
    }

    private void invalidateOverview() {
        try {
            var cache = cacheManager.getCache(AdminCacheConfig.CACHE_RESONANCE_OVERVIEW);
            if (cache != null) cache.clear();
        } catch (Exception e) {
            log.debug("[mq-in] cache clear skipped (Redis down?): {}", e.getMessage());
        }
    }

    private void invalidateTop() {
        try {
            var cache = cacheManager.getCache(AdminCacheConfig.CACHE_RESONANCE_TOP);
            if (cache != null) cache.clear();
        } catch (Exception e) {
            log.debug("[mq-in] cache clear skipped (Redis down?): {}", e.getMessage());
        }
    }

    private static String safeSnippet(String s) {
        if (s == null) return "";
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    private static String driftBottleKey(DriftBottleEvent event) {
        return event.action() + ":" + event.bottleId() + ":" + event.senderUserId()
                + ":" + event.pickerUserId() + ":" + event.occurredAt();
    }

    private static String achievementKey(AchievementUnlockedEvent event) {
        return event.userId() + ":" + event.achievementCode() + ":"
                + event.triggeredByMemoryId() + ":" + event.occurredAt();
    }

    private static String memoryDeleteKey(MemoryDeletedEvent event) {
        return event.memoryId() + ":" + event.userId() + ":" + event.occurredAt();
    }
}
