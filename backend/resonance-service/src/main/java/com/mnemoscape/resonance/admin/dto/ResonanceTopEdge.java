package com.mnemoscape.resonance.admin.dto;

import java.time.OffsetDateTime;

/**
 * Strict-whitelist response record for one entry in
 * {@code GET /api/v1/admin/stats/resonance-top?limit=N} (admin-dashboard
 * task 8.2 / Requirements 12.2 / 12.3).
 *
 * <p>Field rename from the underlying entity (per Requirement 12.3 + design
 * §"#### resonance-service · /admin/** 端点"):
 * <table>
 *   <tr><th>Entity column</th><th>DTO field</th></tr>
 *   <tr><td>{@code memory_id_1}</td><td>{@code memoryAId}</td></tr>
 *   <tr><td>{@code memory_id_2}</td><td>{@code memoryBId}</td></tr>
 *   <tr><td>{@code similarity_score}</td><td>{@code resonanceScore}</td></tr>
 *   <tr><td>{@code status}</td><td>{@code status}</td></tr>
 *   <tr><td>{@code created_at}</td><td>{@code createdAt}</td></tr>
 * </table>
 *
 * <p>Entity fields NOT exposed here (privacy boundary, R15.1 / R12.3):
 * <ul>
 *   <li>{@code emotion_similarity}, {@code scene_similarity} — diagnostic
 *       sub-scores, not part of the dashboard's contract.</li>
 *   <li>{@code scene_data_url} — links to private MinIO objects.</li>
 * </ul>
 *
 * <p>Memory titles / descriptions / scene data live in memory-service —
 * they are <b>never</b> joined into this response. The dashboard only needs
 * the two memory ids to render the force-graph; if the operator wants to
 * inspect a specific edge they navigate to the per-memory views which run
 * the standard memory-service ACL pipeline.
 *
 * @param memoryAId        first memory id in the resonance pair
 * @param memoryBId        second memory id in the resonance pair
 * @param resonanceScore   similarity score in {@code [0, 1]}
 * @param status           edge status string (e.g. {@code "pending"})
 * @param createdAt        edge creation time as a timezone-aware instant
 */
public record ResonanceTopEdge(
        String memoryAId,
        String memoryBId,
        double resonanceScore,
        String status,
        OffsetDateTime createdAt) {
}
