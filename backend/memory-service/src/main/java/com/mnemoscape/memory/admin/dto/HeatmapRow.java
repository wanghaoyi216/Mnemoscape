package com.mnemoscape.memory.admin.dto;

/**
 * Spring Data JPA projection for the global heatmap query in {@code /admin/stats/heatmap}.
 *
 * <p>Each row is one snap-to-grid bucket centered at {@code (latBucket, lngBucket)} (see the
 * native SQL in {@code MemoryRepository#heatmapBuckets}). {@code rawCount} is the un-normalized
 * memory count for that cell — Java post-processing in {@code HeatmapAggregator} divides by the
 * global {@code maxRaw} to produce the {@code intensity ∈ (0, 1]} value emitted to the client.</p>
 *
 * <p>The query already filters {@code privacy_level = 'PUBLIC'} and excludes rows without
 * {@code memory_lat} / {@code memory_lng}, so the projection itself can stay narrow.</p>
 *
 * <p>Validates: admin-dashboard Requirements 9.2, 9.3.</p>
 */
public interface HeatmapRow {

    double getLatBucket();

    double getLngBucket();

    long getRawCount();
}
