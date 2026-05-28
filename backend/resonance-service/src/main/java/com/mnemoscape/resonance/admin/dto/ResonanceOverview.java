package com.mnemoscape.resonance.admin.dto;

import java.util.Map;

/**
 * Strict-whitelist response record for
 * {@code GET /api/v1/admin/stats/resonance-overview} (admin-dashboard
 * task 8.2 / Requirements 12.1).
 *
 * <p>Carries the three KPIs surfaced by the dashboard's resonance panel:
 * <ul>
 *   <li>{@code totalEdges} — count of rows in the {@code resonance_spaces}
 *       table (R12.1).</li>
 *   <li>{@code averageScore} — count-weighted mean of {@code similarity_score}
 *       across all rows. {@code 0.0} when {@code totalEdges == 0}.</li>
 *   <li>{@code statusBreakdown} — {@code status → count} map covering every
 *       distinct status value present in the database. The schema does not
 *       constrain the value set (the column is {@code VARCHAR(20)} with a
 *       string default), so consumers MUST treat unknown keys gracefully —
 *       the frontend uses {@code t('admin.resonance.status.' + status, status)}
 *       to fall back to the raw value.</li>
 * </ul>
 *
 * <p><b>Privacy boundary</b>: this record exposes ZERO memory content
 * (Requirement 15.1). {@code memoryAId} / {@code memoryBId} / per-edge data
 * lives in {@link ResonanceTopEdge}; even there, no titles / descriptions /
 * scene URLs are surfaced. Adding a field requires updating both the design
 * document and the privacy-leak guard tests.
 */
public record ResonanceOverview(
        long totalEdges,
        double averageScore,
        Map<String, Long> statusBreakdown) {
}
