package com.mnemoscape.memory.admin.dto;

import java.time.LocalDateTime;

/**
 * Spring Data JPA projection for the active-user query in {@code /admin/stats/active-user-counts}.
 *
 * <p>Each row represents a single {@code Memory} whose creation OR last modification falls inside the
 * requested window. The {@code latestActivityAt} field is computed at the SQL layer as
 * {@code GREATEST(createdAt, COALESCE(updatedAt, createdAt))} via a JPQL {@code CASE WHEN}
 * expression — see {@code MemoryRepository#findActiveUserRowsBetween}.</p>
 *
 * <p>The Java aggregator (see {@code TimeBucketing} + {@code AdminStatsService.aggregateActiveUsers})
 * groups these rows into UTC-aligned buckets and counts <em>distinct</em> users per bucket.</p>
 *
 * <p>Validates: admin-dashboard Requirements 6.6.</p>
 */
public interface ActiveUserRow {

    String getUserId();

    LocalDateTime getLatestActivityAt();
}
