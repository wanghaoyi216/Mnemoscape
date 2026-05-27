package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.model.entity.Friendship;
import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.FriendshipRepository;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/friends")
public class FriendController {
    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;

    public FriendController(FriendshipRepository friendshipRepository, UserRepository userRepository) {
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
    }

    @PostMapping("/requests")
    public ResponseEntity<ApiResponse<Friendship>> sendRequest(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        String fromUserId = RequestContext.requireUserId(request);
        String targetUsername = body.get("username");
        if (targetUsername == null || targetUsername.isBlank()) {
            throw BizException.badRequest("Target username is required");
        }

        User target = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> BizException.notFound("User", targetUsername));

        if (fromUserId.equals(target.getId())) {
            throw BizException.badRequest("Cannot friend yourself");
        }

        friendshipRepository.findByUserPair(fromUserId, target.getId()).ifPresent(f -> {
            throw new BizException(409, "Friendship already exists");
        });

        Friendship friendship = Friendship.builder()
                .id(UUID.randomUUID().toString())
                .userId1(fromUserId)
                .userId2(target.getId())
                .status(Friendship.FriendshipStatus.PENDING)
                .build();
        friendshipRepository.save(friendship);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(friendship));
    }

    @GetMapping("/requests")
    public ResponseEntity<ApiResponse<List<Friendship>>> getPendingRequests(HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        return ResponseEntity.ok(ApiResponse.success(
                friendshipRepository.findByUserId2AndStatus(userId, Friendship.FriendshipStatus.PENDING)));
    }

    @PutMapping("/requests/{id}/accept")
    public ResponseEntity<ApiResponse<Friendship>> acceptRequest(@PathVariable String id,
                                                                  HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        Friendship friendship = friendshipRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("Friendship", id));

        if (!friendship.getUserId2().equals(userId)) {
            throw BizException.forbidden();
        }
        friendship.setStatus(Friendship.FriendshipStatus.ACCEPTED);
        friendshipRepository.save(friendship);
        return ResponseEntity.ok(ApiResponse.success(friendship));
    }

    @PutMapping("/requests/{id}/reject")
    public ResponseEntity<ApiResponse<Friendship>> rejectRequest(@PathVariable String id,
                                                                  HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        Friendship friendship = friendshipRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("Friendship", id));

        if (!friendship.getUserId2().equals(userId)) {
            throw BizException.forbidden();
        }
        friendship.setStatus(Friendship.FriendshipStatus.REJECTED);
        friendshipRepository.save(friendship);
        return ResponseEntity.ok(ApiResponse.success(friendship));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<User>>> searchUsers(
            @RequestParam String query, HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        if (query == null || query.isBlank()) {
            throw BizException.badRequest("Query is required");
        }
        List<User> matches = userRepository.findByUsernameContainingIgnoreCase(query.trim());
        matches.removeIf(u -> u.getId().equals(userId));
        return ResponseEntity.ok(ApiResponse.success(matches));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<User>>> listFriends(HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        List<Friendship> friendships = friendshipRepository.findAcceptedFriendships(userId);
        List<User> friends = new ArrayList<>();
        for (Friendship f : friendships) {
            String friendId = f.getUserId1().equals(userId) ? f.getUserId2() : f.getUserId1();
            userRepository.findById(friendId).ifPresent(friends::add);
        }
        return ResponseEntity.ok(ApiResponse.success(friends));
    }

    @DeleteMapping("/{friendId}")
    public ResponseEntity<ApiResponse<Void>> removeFriend(@PathVariable String friendId,
                                                           HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        friendshipRepository.findByUserPair(userId, friendId).ifPresent(friendshipRepository::delete);
        return ResponseEntity.ok(ApiResponse.success("Friend removed", null));
    }

    /**
     * 查询当前用户与另一用户的好友关系状态（轻量探针）。
     * 用于服务间调用（例如 memory-service 判断 FRIENDS 隐私级别可访问性）。
     *
     * <p>响应：{@code {"status": "ACCEPTED" | "PENDING" | "REJECTED" | "NONE", "isFriend": boolean}}
     * <ul>
     *   <li>{@code isFriend = true} 当且仅当 status=ACCEPTED</li>
     *   <li>self-check（otherUserId 等于 caller）→ status=NONE, isFriend=false（让上层语义清晰）</li>
     * </ul>
     */
    @GetMapping("/{otherUserId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> friendshipStatus(
            @PathVariable String otherUserId, HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (otherUserId == null || otherUserId.isBlank() || otherUserId.equals(userId)) {
            body.put("status", "NONE");
            body.put("isFriend", false);
            return ResponseEntity.ok(ApiResponse.success(body));
        }
        Friendship.FriendshipStatus status = friendshipRepository.findByUserPair(userId, otherUserId)
                .map(Friendship::getStatus)
                .orElse(null);
        if (status == null) {
            body.put("status", "NONE");
            body.put("isFriend", false);
        } else {
            body.put("status", status.name());
            body.put("isFriend", status == Friendship.FriendshipStatus.ACCEPTED);
        }
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
