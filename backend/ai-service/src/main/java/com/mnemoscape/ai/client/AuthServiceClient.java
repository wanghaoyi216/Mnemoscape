package com.mnemoscape.ai.client;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

/**
 * Feign client to auth-service.
 *
 * <p>v8 新增：AI 工具 {@code getFriendsTool} / {@code getUnreadNotificationsTool}
 * 通过这个 client 调 auth-service 的 {@code /api/v1/friends} 与通知聚合接口。
 * 仅暴露读接口，写操作（发好友请求 / 接受 / 拒绝）刻意不暴露 ——
 * AI 帮用户做"读"的事情是合理的，"写"社交关系是越界。
 *
 * <p>所有方法都强制注入 {@code X-User-Id}，由网关层 ACL 兜底。
 */
@FeignClient(name = "auth-service", contextId = "authService", path = "/api/v1")
public interface AuthServiceClient {

    /**
     * 列出当前用户的所有已接受好友。AI 工具 {@code getFriendsTool} 用。
     *
     * <p>对应 auth-service 的 {@code FriendController.listFriends()}，返回
     * {@code ApiResponse<List<User>>}。我们声明成 {@code List<Map>} 以避免
     * 把 auth 内部的 User 实体反序列化到 ai-service 的 classpath。
     */
    @GetMapping("/friends")
    ApiResponse<List<Map<String, Object>>> listFriends(
            @RequestHeader("X-User-Id") String userId);

    /**
     * v8 暂未实现：项目里没有独立的 notification-service，因此这个端点
     * 在 auth-service 也没注册。{@code getUnreadNotificationsTool} 会调
     * 此方法并在 onError 时返回友好降级（"系统暂未提供通知聚合"）。
     *
     * <p>留这个方法签名是给以后接 notification-service 时零代码改动。
     */
    @GetMapping(value = "/notifications/unread", consumes = "application/json")
    ApiResponse<List<Map<String, Object>>> unreadNotifications(
            @RequestHeader("X-User-Id") String userId);
}
