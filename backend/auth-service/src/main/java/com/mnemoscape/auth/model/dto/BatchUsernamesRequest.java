package com.mnemoscape.auth.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/users/batch-usernames}.
 *
 * <p>Used by memory-service's admin top-contributors endpoint to look up
 * display names for a batch of user ids without ever pulling email,
 * password-hash, or any other sensitive column out of the auth database
 * (admin-dashboard Requirements 10.2 / 10.4 / 15.2).
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code userIds} is required (the body must include the key) but the
 *       list MAY be empty — callers are allowed to pass a zero-length list
 *       when there are no contributors yet, and the endpoint just returns
 *       an empty map.</li>
 *   <li>The list size is hard-capped at {@code 100} entries to defend the
 *       auth database from accidental fan-out: the top-contributors panel
 *       itself caps {@code limit} at 100 (R10.3), so a well-behaved caller
 *       can never exceed this. Bigger requests are rejected with HTTP 400
 *       by Spring's {@code @Valid} machinery before any DB I/O.</li>
 * </ul>
 */
public record BatchUsernamesRequest(
        @NotNull
        @Size(max = 100, message = "userIds must contain at most 100 entries")
        List<String> userIds) {
}
