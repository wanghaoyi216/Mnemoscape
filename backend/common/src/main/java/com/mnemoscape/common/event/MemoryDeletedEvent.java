package com.mnemoscape.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 记忆删除事件。
 *
 * <p><b>Producer</b>：memory-service 在硬/软删除完成后 publish 到
 * {@code mnemoscape.events / memory.deleted}。
 *
 * <p><b>Consumers</b> (扇出两路)：
 * <ul>
 *   <li>ai-service 队列 {@code ai.memory.evict}：删 Milvus 中该 memoryId 的向量行；
 *       调 {@code AiCacheService.invalidateUserSearch(userId)} 清掉该用户所有搜索缓存。</li>
 *   <li>resonance-service 队列 {@code resonance.memory.evict}：清共鸣大厅
 *       feed / top-contributors 缓存（如果被该记忆引用过）。</li>
 * </ul>
 */
public record MemoryDeletedEvent(
        String userId,
        String memoryId,
        Instant occurredAt,
        /** 全局事件 ID；consumer 用它做 at-least-once 投递下的幂等去重。 */
        String eventId
) {
    public static MemoryDeletedEvent of(String userId, String memoryId) {
        return new MemoryDeletedEvent(userId, memoryId, Instant.now(), newEventId());
    }

    private static String newEventId() {
        return UUID.randomUUID().toString();
    }
}
