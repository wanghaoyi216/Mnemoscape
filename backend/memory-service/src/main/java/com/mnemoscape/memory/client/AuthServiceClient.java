package com.mnemoscape.memory.client;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Feign client to auth-service for 好友关系探针。
 *
 * <p>用于 {@code MemoryService.checkAccess} 在 FRIENDS 隐私级别下判断
 * 调用方是否被记忆所有者接受为好友。
 *
 * <p>响应 schema：{@code {"status": "ACCEPTED"|"PENDING"|"REJECTED"|"NONE", "isFriend": boolean}}
 *
 * <p>调用约定：把当前请求方的 userId 放在 {@code X-User-Id} header 透传过去；
 * auth-service 用此头识别"caller"，然后比对 path 参数 {@code otherUserId}。
 */
@FeignClient(name = "auth-service", contextId = "authServiceForFriendship", path = "/api/v1/friends")
public interface AuthServiceClient {

    @GetMapping("/{otherUserId}/status")
    ApiResponse<Map<String, Object>> friendshipStatus(
            @PathVariable("otherUserId") String otherUserId,
            @RequestHeader("X-User-Id") String callerUserId);
}
