package com.mnemoscape.memory.admin.dto;

/**
 * Strict-whitelist response record for one entry in
 * {@code GET /api/v1/admin/stats/top-contributors} (admin-dashboard task
 * 7.17 / Requirements 10.1–10.4).
 *
 * <p>Three fields, all carefully chosen:
 * <ul>
 *   <li>{@code userId} — identifier so the dashboard can deep-link if needed.</li>
 *   <li>{@code username} — display name, resolved via auth-service's
 *       batch-username Feign endpoint. When that lookup fails the field
 *       degrades to {@code userId.substring(0, 8)} (R18.1).</li>
 *   <li>{@code memoryCount} — number of memories created by this user
 *       inside the requested window.</li>
 * </ul>
 *
 * <p>Privacy boundary: this is the strictest whitelist on the dashboard.
 * {@code email}, {@code passwordHash}, {@code role}, {@code verified},
 * {@code avatarUrl}, {@code backgroundImageUrl}, account timestamps —
 * NONE leak through. Auth-service's batch-username endpoint returns
 * {@code username} only and the projection record stops there.
 * (Requirements 10.4 / 15.2)
 */
public record TopContributor(String userId, String username, long memoryCount) {
}
