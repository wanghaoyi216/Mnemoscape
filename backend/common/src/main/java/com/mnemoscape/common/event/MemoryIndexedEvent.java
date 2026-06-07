package com.mnemoscape.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 记忆"已落库且应被索引"的领域事件。
 *
 * <p><b>Producer</b>：memory-service 在
 * {@code MemoryService.createMemory / updateMemory} 落库 + 主流程响应成功之后
 * publish 到 {@code mnemoscape.events / memory.indexed}。
 *
 * <p><b>Consumer</b>：ai-service 监听 {@code ai.memory.index} 队列后做两件事：
 * <ol>
 *   <li>调 {@link com.mnemoscape.common.constant.ServiceConstants#SERVICE_AI}
 *       内部的 VectorIndexService 把 (title + description + ...) 切片 embedding
 *       并 upsert 到 Milvus；</li>
 *   <li>调 Neo4j 工具把 (User)-[:OWNS]-&gt;(Memory)-[:HAPPENED_AT]-&gt;(Location/Year/...)
 *       的子图写入 / 更新。</li>
 * </ol>
 *
 * <p><b>为何用 record</b>：契约不可变 + 自动 equals/toString + Jackson 友好。
 *
 * <p><b>幂等</b>：Consumer 必须用 {@code memoryId} 当作 upsert key，重复消费不应
 * 产生重复向量。投递语义是 at-least-once（RabbitMQ 默认）。
 */
public record MemoryIndexedEvent(
        String userId,
        String memoryId,
        String title,
        String location,
        Integer year,
        String description,
        /** "PRIVATE" / "FRIENDS" / "PUBLIC"；共鸣大厅的公共检索依赖它过滤。null 时按 PRIVATE 处理。 */
        String privacy,
        /** "created" / "updated"，给 Consumer 做日志/埋点分桶 */
        String action,
        Instant occurredAt,
        /** 全局事件 ID；consumer 用它做 at-least-once 投递下的幂等去重。 */
        String eventId
) {
    public static MemoryIndexedEvent created(String userId, String memoryId, String title,
                                             String location, Integer year, String description,
                                             String privacy) {
        return new MemoryIndexedEvent(userId, memoryId, title, location, year, description,
                privacy, "created", Instant.now(), newEventId());
    }

    public static MemoryIndexedEvent updated(String userId, String memoryId, String title,
                                             String location, Integer year, String description,
                                             String privacy) {
        return new MemoryIndexedEvent(userId, memoryId, title, location, year, description,
                privacy, "updated", Instant.now(), newEventId());
    }

    private static String newEventId() {
        return UUID.randomUUID().toString();
    }
}
