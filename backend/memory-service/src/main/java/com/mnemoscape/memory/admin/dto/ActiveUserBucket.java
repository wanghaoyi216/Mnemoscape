package com.mnemoscape.memory.admin.dto;

/**
 * Public response record for {@code GET /api/v1/admin/stats/active-user-counts}
 * (admin-dashboard task 7.8 / Requirement 6.6).
 *
 * <p>This is the wire shape returned to auth-service via Feign — the auth
 * side then forwards the same payload to the dashboard. Both ends use a
 * record with identical field names so Jackson round-trips cleanly without
 * a custom deserializer.
 *
 * <p>Strict whitelist: only the two fields below are serialized. Adding a
 * new field requires updating the design document and the privacy-leak
 * guard tests (Property 9 / Requirement 15.1).
 *
 * @param bucket           bucket key in the dimension's canonical print form
 *                         (e.g. {@code "2026-05-24"} for DAILY)
 * @param activeUserCount  zero-filled distinct active user count for the bucket
 */
public record ActiveUserBucket(String bucket, long activeUserCount) {
}
