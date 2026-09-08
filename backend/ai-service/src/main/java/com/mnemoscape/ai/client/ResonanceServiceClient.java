package com.mnemoscape.ai.client;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
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

    /**
     * 共鸣池公共数据。AI 工具 {@code getResonanceFeedTool} 用：模型在拿到"这个
     * 话题的共鸣池里最近什么记忆最热"时调一次（即使它没具体 memoryId，
     * ResonanceService.searchResonances 也能用空 memoryId 返回公共池片段，
     * 由 service 自己 short-circuit）。
     *
     * <p>getResonanceFeedTool 实际只读 stats，因为强召回会触发 RAG 双算。
     */
    @GetMapping("/resonances/stats")
    ApiResponse<Map<String, Object>> stats();

    /**
     * 列出当前用户与某好友 / 群组的最近聊天历史。AI 工具
     * {@code listChatHistoryTool} 用。
     *
     * <p>对应 {@code resonance-service} 的 {@code GET /api/v1/chat/messages}。
     * 与 listResonances 的 X-User-Id 透传一致。
     */
    @GetMapping("/chat/messages")
    ApiResponse<List<Map<String, Object>>> listChatMessages(
            @RequestParam(value = "receiverId", required = false) String receiverId,
            @RequestParam(value = "groupId", required = false) String groupId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestHeader("X-User-Id") String userId);
}
