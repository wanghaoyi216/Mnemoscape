package com.mnemoscape.ai.messaging;

import com.mnemoscape.ai.service.AiCacheService;
import com.mnemoscape.ai.service.VectorIndexService;
import com.mnemoscape.common.event.MemoryDeletedEvent;
import com.mnemoscape.common.event.MemoryIndexedEvent;
import com.mnemoscape.common.idempotency.MessageDedupGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * ai-service 端 Consumer — 把 memory-service 扇出的领域事件落地为
 * Milvus 向量库的读写 + 搜索缓存失效。
 *
 * <p><b>幂等</b>：向量库 upsert / delete 都用 memoryId 当主键，重投不会
 * 产生重复记录。投递语义 at-least-once + 幂等消费 = 实际效果接近 exactly-once。
 *
 * <p><b>失败处理</b>：方法体不抛异常 — 业务失败一律 log + 吞掉，让
 * listener 容器走"ack"路径，避免坏消息在队列里循环 4 次后被
 * {@code RejectAndDontRequeueRecoverer} 推到 DLX（可人工排查）。
 * 真正的临时性失败（Milvus 抖动）由 listener 的 retry (4 attempts,
 * 1s→2s→4s→8s) 处理。
 */
@Component
public class MemoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(MemoryEventConsumer.class);

    private final VectorIndexService vectorIndexService;
    private final AiCacheService aiCacheService;
    private final MessageDedupGuard dedupGuard;

    public MemoryEventConsumer(VectorIndexService vectorIndexService,
                               AiCacheService aiCacheService,
                               MessageDedupGuard dedupGuard) {
        this.vectorIndexService = vectorIndexService;
        this.aiCacheService = aiCacheService;
        this.dedupGuard = dedupGuard;
    }

    /** memory.indexed → Milvus upsert。 */
    @RabbitListener(queues = "${mnemoscape.mq.queue.ai-memory-index:" +
            "ai.memory.index}", containerFactory = "rabbitListenerContainerFactory")
    public void onMemoryIndexed(MemoryIndexedEvent event) {
        if (event == null || event.memoryId() == null) {
            log.warn("[mq-in] memory.indexed event is null or missing memoryId, skip");
            return;
        }
        var lease = dedupGuard.tryBegin("ai.memory.indexed", event.eventId(), memoryIndexKey(event));
        if (lease.isEmpty()) {
            return;
        }
        try {
            boolean ok = vectorIndexService.index(
                    event.memoryId(),
                    event.userId(),
                    event.title(),
                    event.location(),
                    event.year(),
                    event.description(),
                    event.privacy());
            log.info("[mq-in] memory.indexed memoryId={} user={} action={} indexed={}",
                    event.memoryId(), event.userId(), event.action(), ok);
            lease.get().markProcessed();
        } catch (Exception e) {
            lease.get().clear();
            // best-effort: 失败不抛，外层 listener 走 retry/DLX
            log.warn("[mq-in] memory.indexed handler failed for {}: {}",
                    event.memoryId(), e.toString());
        }
    }

    /** memory.deleted → Milvus delete + 该用户搜索缓存清空。 */
    @RabbitListener(queues = "${mnemoscape.mq.queue.ai-memory-evict:" +
            "ai.memory.evict}", containerFactory = "rabbitListenerContainerFactory")
    public void onMemoryDeleted(MemoryDeletedEvent event) {
        if (event == null || event.memoryId() == null) {
            log.warn("[mq-in] memory.deleted event is null or missing memoryId, skip");
            return;
        }
        var lease = dedupGuard.tryBegin("ai.memory.deleted", event.eventId(), memoryDeleteKey(event));
        if (lease.isEmpty()) {
            return;
        }
        try {
            boolean deleted = vectorIndexService.delete(event.memoryId());
            int cacheCleared = 0;
            if (event.userId() != null) {
                cacheCleared = aiCacheService.invalidateUserSearch(event.userId());
            }
            log.info("[mq-in] memory.deleted memoryId={} user={} vectorDeleted={} cacheCleared={}",
                    event.memoryId(), event.userId(), deleted, cacheCleared);
            lease.get().markProcessed();
        } catch (Exception e) {
            lease.get().clear();
            log.warn("[mq-in] memory.deleted handler failed for {}: {}",
                    event.memoryId(), e.toString());
        }
    }

    private static String memoryIndexKey(MemoryIndexedEvent event) {
        return event.memoryId() + ":" + event.action() + ":" + event.occurredAt();
    }

    private static String memoryDeleteKey(MemoryDeletedEvent event) {
        return event.memoryId() + ":" + event.userId() + ":" + event.occurredAt();
    }
}
