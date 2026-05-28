package com.mnemoscape.memory.client.dto;

import java.util.List;

/**
 * Wire-side request body for {@code POST /api/v1/users/batch-usernames}
 * on the memory-service caller end.
 *
 * <p>This mirrors auth-service's
 * {@code com.mnemoscape.auth.model.dto.BatchUsernamesRequest} but is
 * declared locally so memory-service does not need a Maven dependency
 * on auth-service. The wire shape (a single JSON object with a
 * {@code "userIds"} array) is what binds the two ends.
 *
 * <p>Caller responsibility: enforce the {@code ≤ 100} cap before dispatch
 * (admin-dashboard R10.2 / R10.3). The receiving side's {@code @Valid}
 * machinery rejects oversized lists with HTTP 400, which Feign translates
 * into a {@link feign.FeignException} that the top-contributors degraded
 * path already catches.
 */
public record BatchUsernamesRequest(List<String> userIds) {
}
