package com.mnemoscape.auth.model.dto;

/**
 * Request body for {@code POST /api/v1/admin/users/{userId}/role}.
 *
 * <p>The role-promotion endpoint accepts a single field {@code role} that
 * must equal the literal string {@code "ADMIN"}; any other value (including
 * {@code "USER"} or a typo) is rejected with HTTP 400 {@code INVALID_ROLE}
 * by the controller (admin-dashboard task 6.4 / Requirements 1.4).
 *
 * <p>The shape is deliberately permissive at the JSON layer (no
 * {@code @NotBlank} / {@code @Pattern} annotations) so the controller
 * itself can choose the exact failure message — Spring's bean-validation
 * machinery would otherwise wrap rejections in a generic
 * {@code "Validation failed"} message that does not match the
 * {@code INVALID_ROLE} contract documented in the design.</p>
 *
 * <p>Demoting a user to {@code "USER"} is not in scope for this endpoint;
 * the design only describes promotion. Future work that introduces a demote
 * pathway will use a separate endpoint with its own audit + metric tags.</p>
 */
public record RolePromotionRequest(String role) {
}
