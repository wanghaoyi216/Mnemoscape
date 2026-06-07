package com.mnemoscape.auth.model.dto;

import java.util.Map;

/**
 * Response payload for {@code POST /api/v1/users/batch-usernames}.
 *
 * <p>Strict whitelist: the only field is a {@code Map<userId, username>}.
 * Email, password hash, avatar URL, role, verified flag and timestamps are
 * <b>intentionally omitted</b> — admin-dashboard Requirements 10.4 / 15.2
 * forbid leaking any column outside {@code {userId, username}} from this
 * lookup endpoint, even to a trusted internal Feign caller.
 *
 * <p>Lookup semantics: ids that don't resolve to a user row simply do not
 * appear as keys in the map. This lets the caller (memory-service
 * top-contributors aggregator) fall back to a deterministic placeholder
 * (R18.1's {@code userId.substring(0, 8)}) without needing a separate
 * "missing ids" channel.
 */
public record BatchUsernamesResponse(Map<String, String> usernames) {
}
