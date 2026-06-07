package com.mnemoscape.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 漂流瓶事件 — 投放 (thrown) 或 被捞起 (picked) 共用一个 DTO，
 * 通过 routing key 区分: {@code driftbottle.thrown} / {@code driftbottle.picked}.
 *
 * <p><b>Producer</b>：memory-service {@code DriftBottleService} 在写库后 publish。
 *
 * <p><b>Consumer</b>：resonance-service {@code resonance.driftbottle} 队列：
 * <ul>
 *   <li>thrown → 写入共鸣大厅 feed 缓存 / 实时推流 (未来 WebSocket)；
 *       失效 admin.resonance-overview / admin.resonance-top 这两个 60s 缓存。</li>
 *   <li>picked → 给 sender / receiver 双方写互动通知 (走 ChatMessage 系统消息);
 *       失效相关缓存。</li>
 * </ul>
 */
public record DriftBottleEvent(
        /** "thrown" / "picked"，对应 routing key 后缀；冗余但便于 Consumer 单队列下分支 */
        String action,
        String bottleId,
        /** 投放者 userId（thrown 时 = 投放者；picked 时 = 原投放者，用于通知"被谁捞到") */
        String senderUserId,
        /** 捞起者 userId；thrown 阶段为 null */
        String pickerUserId,
        /** 漂流瓶内容摘要（前 120 字），供 feed 渲染不必再查 DB */
        String preview,
        /** 关联的 memoryId；可能为 null（漂流瓶不一定绑定记忆） */
        String relatedMemoryId,
        Instant occurredAt,
        /** 全局事件 ID；consumer 用它做 at-least-once 投递下的幂等去重。 */
        String eventId
) {
    public static DriftBottleEvent thrown(String bottleId, String senderUserId,
                                          String preview, String relatedMemoryId) {
        return new DriftBottleEvent("thrown", bottleId, senderUserId, null,
                preview, relatedMemoryId, Instant.now(), newEventId());
    }

    public static DriftBottleEvent picked(String bottleId, String senderUserId, String pickerUserId,
                                          String preview, String relatedMemoryId) {
        return new DriftBottleEvent("picked", bottleId, senderUserId, pickerUserId,
                preview, relatedMemoryId, Instant.now(), newEventId());
    }

    private static String newEventId() {
        return UUID.randomUUID().toString();
    }
}
