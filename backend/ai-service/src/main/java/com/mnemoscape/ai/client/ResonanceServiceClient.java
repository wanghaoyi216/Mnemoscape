package com.mnemoscape.ai.client;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Feign client to resonance-service.
 *
 * <p>用于 {@code SupportTicketTool} 让 AI 帮用户提交客服工单 —— 当用户在对话中
 * 抱怨「这个功能挂了 / 我想反馈一个 bug」时，星空使者可以代用户调用此端点
 * 创建工单，省去用户手动开浮动客服窗的步骤。
 *
 * <p>仅暴露 create，避免 AI 误删 / 误改用户工单。所有调用都强制注入
 * {@code X-User-Id}，由网关层 ACL 兜底。
 */
@FeignClient(name = "resonance-service", contextId = "resonanceService", path = "/api/v1")
public interface ResonanceServiceClient {

    @PostMapping("/support/tickets")
    ApiResponse<Map<String, Object>> createTicket(
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-User-Id") String userId);
}
