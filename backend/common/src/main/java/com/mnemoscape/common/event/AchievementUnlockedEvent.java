package com.mnemoscape.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 成就解锁事件。
 *
 * <p><b>Producer</b>：memory-service {@code AchievementService.unlock(...)}
 * 在写库 (achievements 表) 之后 publish 到
 * {@code mnemoscape.events / achievement.unlocked}.
 *
 * <p><b>Consumer</b>：resonance-service {@code resonance.achievement} 队列：
 * <ul>
 *   <li>给用户写系统通知 (走 ChatMessage 系统消息或 future notifications 表)；</li>
 *   <li>失效 admin.resonance-top / memory-service admin.top-contributors 缓存
 *       (通过 Feign 调 admin API 触发，或简单 publish 一条 evict 事件)。</li>
 * </ul>
 */
public record AchievementUnlockedEvent(
        String userId,
        /** 业务编码，如 "FIRST_MEMORY" / "SOCIAL_BUTTERFLY" */
        String achievementCode,
        /** 展示名，给通知文案直接用 */
        String name,
        /** 解锁触发的源记忆 ID（可空：有些成就与某条记忆强绑定） */
        String triggeredByMemoryId,
        Instant occurredAt,
        /** 全局事件 ID；consumer 用它做 at-least-once 投递下的幂等去重。 */
        String eventId
) {
    public static AchievementUnlockedEvent of(String userId, String code, String name,
                                              String triggeredByMemoryId) {
        return new AchievementUnlockedEvent(userId, code, name, triggeredByMemoryId,
                Instant.now(), newEventId());
    }

    private static String newEventId() {
        return UUID.randomUUID().toString();
    }
}
