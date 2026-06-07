package com.mnemoscape.memory.admin.dto;

/**
 * Strict-whitelist response record for one row of
 * {@code GET /api/v1/admin/stats/fragment-discovery?groupBy=fragmentType}
 * (admin-dashboard task 7.19 / Requirement 11.3).
 *
 * <p>Adds {@code fragmentType} to the schema of
 * {@link FragmentDiscoveryOverall}; the controller branches on the presence
 * of {@code groupBy} to decide which record to render.
 *
 * <p>Privacy boundary: only the type label and aggregate counts are
 * exposed (Requirement 15.1).
 */
public record FragmentDiscoveryByType(
        String fragmentType,
        long totalFragments,
        long discoveredFragments,
        double discoveryRate) {
}
