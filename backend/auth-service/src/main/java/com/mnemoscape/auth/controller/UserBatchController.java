package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.model.dto.BatchUsernamesRequest;
import com.mnemoscape.auth.model.dto.BatchUsernamesResponse;
import com.mnemoscape.auth.repository.IdUsernameProjection;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Internal lookup endpoint used by memory-service top-contributors aggregator.
 *
 * <p>Exposes {@code POST /api/v1/users/batch-usernames}: given a list of
 * user ids (≤ 100), returns a {@link Map} from id to username. Ids that
 * don't exist in the {@code users} table are simply absent from the map —
 * the caller (memory-service) falls back to a deterministic placeholder
 * ({@code userId.substring(0, 8)}) when looking up missing keys, which
 * keeps the contract robust without requiring a separate "not found"
 * channel (admin-dashboard design.md §"top-contributors JPQL").
 *
 * <p><b>Privacy boundary (Requirements 10.4 / 15.2)</b>: the response
 * shape is a strict whitelist — only {@code username} is ever serialised.
 * The repository deliberately uses a constructor-expression JPQL projection
 * so {@code email}, {@code passwordHash}, {@code role}, {@code verified}
 * and timestamp columns never leave the database in the first place.
 *
 * <p><b>Why a list instead of a query parameter</b>: the canonical
 * {@code limit=100} caller would otherwise pack a comma-separated string
 * into the URL and we'd hit some servers' {@code maxHttpHeaderSize} (8 KB
 * default for Tomcat) once the ids are UUIDs. POST + JSON body sidesteps
 * that and lets {@code @Valid} + {@code @Size(max=100)} cap the fan-out
 * in a single annotation.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserBatchController {

    private final UserRepository userRepository;

    public UserBatchController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping("/batch-usernames")
    public ResponseEntity<ApiResponse<BatchUsernamesResponse>> batchUsernames(
            @Valid @RequestBody BatchUsernamesRequest request) {

        // De-duplicate up-front so a request with [a, a, a] becomes a single
        // {@code IN (a)} predicate; preserves ordering for log readability
        // while keeping the SQL clause small.
        List<String> rawIds = request.userIds();
        if (rawIds == null || rawIds.isEmpty()) {
            return ResponseEntity.ok(
                    ApiResponse.success(new BatchUsernamesResponse(Map.of())));
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : rawIds) {
            if (id != null && !id.isBlank()) {
                unique.add(id);
            }
        }
        if (unique.isEmpty()) {
            return ResponseEntity.ok(
                    ApiResponse.success(new BatchUsernamesResponse(Map.of())));
        }

        List<IdUsernameProjection> rows = userRepository.findIdUsernameByIdIn(unique);
        Map<String, String> usernames = new HashMap<>(rows.size() * 2);
        for (IdUsernameProjection row : rows) {
            usernames.put(row.id(), row.username());
        }
        return ResponseEntity.ok(
                ApiResponse.success(new BatchUsernamesResponse(usernames)));
    }
}
