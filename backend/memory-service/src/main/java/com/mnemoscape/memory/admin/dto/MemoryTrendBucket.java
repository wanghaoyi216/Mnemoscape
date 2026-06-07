package com.mnemoscape.memory.admin.dto;

/**
 * Public response record for {@code GET /api/v1/admin/stats/memory-trends}
 * (admin-dashboard task 7.9 / Requirements 7.1–7.3).
 *
 * <p>Each bucket carries two counts:
 * <ul>
 *   <li>{@code createdCount} — number of memories whose {@code createdAt}
 *       falls inside the bucket's UTC half-open range</li>
 *   <li>{@code modifiedCount} — number of memories whose {@code updatedAt}
 *       is strictly greater than {@code createdAt} AND falls inside the
 *       bucket. Initial inserts (where {@code updatedAt == createdAt}) are
 *       NOT counted as modifications.</li>
 * </ul>
 *
 * <p>Strict whitelist: only the three fields below are serialized.
 * Memory titles, descriptions, location, emotion data — none of it leaves
 * the service through this endpoint (Requirement 15.1).
 *
 * @param bucket          bucket key in the dimension's canonical print form
 * @param createdCount    memories created in the bucket
 * @param modifiedCount   memories modified in the bucket (excluding initial inserts)
 */
public record MemoryTrendBucket(String bucket, long createdCount, long modifiedCount) {
}
