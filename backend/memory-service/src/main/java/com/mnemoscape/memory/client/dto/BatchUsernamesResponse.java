package com.mnemoscape.memory.client.dto;

import java.util.Map;

/**
 * Wire-side response payload for {@code POST /api/v1/users/batch-usernames}
 * on the memory-service caller end.
 *
 * <p>This mirrors auth-service's
 * {@code com.mnemoscape.auth.model.dto.BatchUsernamesResponse}: a single
 * {@code Map<userId, username>} with no email / password-hash / other
 * fields (admin-dashboard R10.4 / R15.2). Defined locally to avoid a
 * cross-module Maven dependency.
 *
 * <p>Ids that resolve to no user row are simply absent from the map. The
 * top-contributors aggregator handles that by falling back to
 * {@code userId.substring(0, 8)} for every missing key (R18.1).
 */
public record BatchUsernamesResponse(Map<String, String> usernames) {
}
