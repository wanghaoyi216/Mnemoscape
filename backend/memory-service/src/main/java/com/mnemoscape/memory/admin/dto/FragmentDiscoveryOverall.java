package com.mnemoscape.memory.admin.dto;

/**
 * Strict-whitelist response record for the global variant of
 * {@code GET /api/v1/admin/stats/fragment-discovery} (no {@code groupBy}
 * parameter — admin-dashboard task 7.19 / Requirements 11.1, 11.2).
 *
 * <p>Three fields:
 * <ul>
 *   <li>{@code totalFragments} — total fragments across the platform</li>
 *   <li>{@code discoveredFragments} — fragments where {@code is_discovered = true}</li>
 *   <li>{@code discoveryRate} — {@code totalFragments == 0 ? 0.0 :
 *       discoveredFragments / totalFragments} (R11.2)</li>
 * </ul>
 *
 * <p>Privacy boundary: only aggregate counts and the derived rate. No
 * fragment ids, content, position data, or trigger conditions ever leak
 * through (Requirement 15.1).
 */
public record FragmentDiscoveryOverall(
        long totalFragments,
        long discoveredFragments,
        double discoveryRate) {
}
