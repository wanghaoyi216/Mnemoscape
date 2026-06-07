package com.mnemoscape.memory.messaging.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 拉取一批"可发"事件 —— status=PENDING 且 nextAttemptAt ≤ now，
     * 按 id 升序保证 FIFO（先到的先发）。
     *
     * <p>限制 50 行/批：防止 scheduler 单轮扫太多导致 broker / DB 抖动；
     * 每 30s 一轮 → 持续吞吐 50/30s = 1.7/s 远低于 6 service 当前事件量级。
     */
    @Query("""
            SELECT e FROM OutboxEvent e
             WHERE e.status = :status
               AND e.nextAttemptAt <= :now
             ORDER BY e.id ASC
            """)
    List<OutboxEvent> findReady(@Param("status") OutboxEvent.Status status,
                                  @Param("now") Instant now,
                                  Pageable pageable);

    /** 扫表清理用 —— 看 outbox 积压情况，做监控指标。 */
    long countByStatus(OutboxEvent.Status status);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.id <= :maxId")
    int deletePublishedOlderThan(@Param("status") OutboxEvent.Status status,
                                   @Param("maxId") long maxId);
}
