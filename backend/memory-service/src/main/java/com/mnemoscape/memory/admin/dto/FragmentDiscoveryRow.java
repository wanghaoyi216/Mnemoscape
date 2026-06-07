package com.mnemoscape.memory.admin.dto;

/**
 * Spring Data JPA projection for the fragment-discovery aggregation in
 * {@code /admin/stats/fragment-discovery}.
 *
 * <p>Two repository methods reuse this shape:</p>
 * <ul>
 *   <li>{@code MemoryRepository#aggregateFragmentByType()} — one row per {@code fragmentType};
 *       {@code getFragmentType()} is non-null.</li>
 *   <li>{@code MemoryRepository#aggregateFragmentOverall()} — single row, global counts;
 *       {@code getFragmentType()} returns {@code null}.</li>
 * </ul>
 *
 * <p>{@code discoveryRate} is computed by the controller as
 * {@code totalFragments == 0 ? 0.0 : discoveredFragments / totalFragments} per Requirements
 * 11.2 — the projection itself stays close to the SQL aggregates.</p>
 *
 * <p>Validates: admin-dashboard Requirements 11.1, 11.3.</p>
 */
public interface FragmentDiscoveryRow {

    /** {@code null} for the overall variant; non-null for the {@code GROUP BY fragmentType} variant. */
    String getFragmentType();

    long getTotalFragments();

    long getDiscoveredFragments();
}
