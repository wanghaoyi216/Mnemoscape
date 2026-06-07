package com.mnemoscape.auth.model.dto;

/**
 * Single bucket row in the response of
 * {@code GET /api/v1/admin/stats/active-users} (auth-service-side) and
 * {@code GET /api/v1/admin/stats/active-user-counts} (memory-service-side
 * internal endpoint).
 *
 * <p>Shape per admin-dashboard design.md §"#### auth-service · `/admin/**`
 * 端点 → §2 active-users → Response DTO" and Requirements 6.1.
 *
 * <p>Strict whitelist record: only the two fields below are serialized.
 * Adding any new field requires updating the design document and the
 * privacy-leak guard tests (Property 9 / Requirement 15.1).
 *
 * @param bucket           bucket key in the dimension's canonical print form,
 *                         e.g. {@code "2026-05-24"} (DAILY),
 *                         {@code "2026-W21"} (WEEKLY),
 *                         {@code "2026-05"} (MONTHLY),
 *                         {@code "2026"} (YEARLY)
 * @param activeUserCount  zero-filled distinct active user count for the bucket
 */
public record ActiveUserBucket(String bucket, long activeUserCount) {
}
