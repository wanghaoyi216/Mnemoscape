package com.mnemoscape.auth.model.dto;

/**
 * Response payload for {@code POST /api/v1/admin/users/{userId}/role}.
 *
 * <p>Strict whitelist: only the three fields below are serialized into the
 * {@code data} field of the {@code ApiResponse} envelope.
 * (admin-dashboard task 6.4 / design.md §"#### auth-service · /admin/** 端点 → §1")
 *
 * <ul>
 *   <li>{@code userId} — the promoted user's id (echo of the path param).</li>
 *   <li>{@code role} — the resulting role; always {@code "ADMIN"} for the
 *       two terminal success branches.</li>
 *   <li>{@code result} — discriminator describing the outcome:
 *       {@code "promoted"} for an actual role change, {@code "already-admin"}
 *       for the idempotent no-op when the target was already an ADMIN.</li>
 * </ul>
 *
 * <p>Reject branches ({@code BOOTSTRAP_DISABLED}, {@code BOOTSTRAP_REJECTED},
 * {@code USER_NOT_FOUND}, {@code INVALID_ROLE}) never produce this record —
 * they throw {@link com.mnemoscape.common.exception.BizException} which the
 * global handler maps to the appropriate {@code ApiResponse.error(...)}
 * payload with {@code data = null}.
 */
public record RolePromotionResponse(String userId, String role, String result) {
}
