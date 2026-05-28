package com.mnemoscape.memory.client;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.memory.client.dto.BatchUsernamesRequest;
import com.mnemoscape.memory.client.dto.BatchUsernamesResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Feign client to {@code auth-service} covering the two cross-service
 * lookups memory-service depends on:
 *
 * <ol>
 *   <li><b>Friendship probe</b> ({@code GET /api/v1/friends/{otherUserId}/status})
 *       — used by {@code MemoryService.checkAccess} on FRIENDS-level memories
 *       to confirm caller and owner are mutually accepted friends.</li>
 *   <li><b>Batch username lookup</b>
 *       ({@code POST /api/v1/users/batch-usernames}) — used by the admin
 *       top-contributors aggregator to resolve {@code (userId → username)}
 *       for the small (≤ 100) set of contributors returned by JPQL on the
 *       {@code memories} table.</li>
 * </ol>
 *
 * <p>Both methods share a single {@code @FeignClient} declaration so we
 * don't pay the cost of two separate context children for the same target
 * service. The contextId is intentionally generic ({@code authService})
 * rather than scoped to a single use case so any future auth-service
 * lookup added by memory-service has an obvious home.
 *
 * <p><b>Response wire shape (batch-usernames)</b>: auth-service wraps the
 * payload in {@link ApiResponse}, so the {@code data} field is a
 * {@link BatchUsernamesResponse} record carrying a single
 * {@code Map<userId, username>}. Ids that don't match a real user row are
 * silently absent from the map — top-contributors falls back to
 * {@code userId.substring(0, 8)} for every missing key (admin-dashboard
 * R18.1).
 */
@FeignClient(name = "auth-service", contextId = "authService", path = "/api/v1")
public interface AuthServiceClient {

    /**
     * Returns the friendship status between the caller and {@code otherUserId}.
     *
     * <p>Wire route: {@code GET /api/v1/friends/{otherUserId}/status}.
     *
     * <p>Response schema:
     * {@code {"status": "ACCEPTED"|"PENDING"|"REJECTED"|"NONE", "isFriend": boolean}}
     * — {@code isFriend = true} iff status == ACCEPTED.
     */
    @GetMapping("/friends/{otherUserId}/status")
    ApiResponse<Map<String, Object>> friendshipStatus(
            @PathVariable("otherUserId") String otherUserId,
            @RequestHeader("X-User-Id") String callerUserId);

    /**
     * Look up usernames for a batch of user ids (admin-dashboard R10.2).
     *
     * <p>Wire route: {@code POST /api/v1/users/batch-usernames}.
     *
     * <p>Auth-service caps the request size at 100 entries (R10.3 + R10.2).
     * Ids absent from {@code users} simply don't appear as map keys, so the
     * caller can iterate the original list and apply a default for misses
     * without an explicit "missing" channel.
     *
     * <p>Privacy boundary: the response payload is a strict whitelist —
     * {@code username} only. Email / password hash / role / verified flags
     * are never serialised (R10.4 / R15.2).
     */
    @PostMapping("/users/batch-usernames")
    ApiResponse<BatchUsernamesResponse> batchUsernames(
            @RequestBody BatchUsernamesRequest request);
}
