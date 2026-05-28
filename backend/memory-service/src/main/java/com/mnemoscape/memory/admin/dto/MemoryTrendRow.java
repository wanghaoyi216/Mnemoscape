package com.mnemoscape.memory.admin.dto;

import java.time.LocalDateTime;

/**
 * Spring Data JPA projection for the memory-trends query in {@code /admin/stats/memory-trends}.
 *
 * <p>The query selects every memory whose {@code createdAt} OR {@code updatedAt} falls inside the
 * requested window. Bucketing into DAILY / WEEKLY / MONTHLY / YEARLY series is done in Java
 * (see {@code TimeBucketing} + {@code AdminStatsService.aggregateMemoryTrendBuckets}) so we can
 * share one window-membership predicate per row across both the {@code createdCount} and
 * {@code modifiedCount} dimensions.</p>
 *
 * <p>Validates: admin-dashboard Requirements 7.2.</p>
 */
public interface MemoryTrendRow {

    String getId();

    LocalDateTime getCreatedAt();

    LocalDateTime getUpdatedAt();
}
