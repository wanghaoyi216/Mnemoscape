package com.mnemoscape.resonance.client;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Feign client to ai-service for真实向量召回（共鸣大厅）+ 聊天室 AI 助手。
 *
 * <p>共鸣大厅原本用"关键词 jaccard + 季节/年代/地点加权"做相似度（稀疏向量替身）。
 * 接入 ai-service 的真实 Embedding + Milvus 后，{@link com.mnemoscape.resonance.service.ResonanceService}
 * 优先用 {@code /vector/search-public} 做跨用户稠密向量召回；ai-service 返回
 * {@code available=false}（Embedding / Milvus 不可用）时透明降级回关键词打分。
 *
 * <p>聊天室融合（设计书 §3.2.3）：群聊 {@code @AI} / 私聊破冰按钮经
 * {@link com.mnemoscape.resonance.service.ChatAiAssistant} 调 {@code /reconstruct/chat}
 * 同步对话端点，让"星空使者"结合最近聊天历史生成回复 / 破冰建议。
 */
@FeignClient(name = "ai-service", contextId = "aiServiceForResonance")
public interface AiServiceClient {

    /**
     * 跨用户公共向量检索。请求体：seedText / excludeUserId / topK。
     * 响应 data 形如 {@code {available: boolean, hits: [{memoryId,userId,title,location,year,snippet,score}]}}。
     */
    @PostMapping("/api/v1/vector/search-public")
    ApiResponse<Map<String, Object>> searchPublic(@RequestBody Map<String, Object> request);

    /**
     * 同步对话端点。请求体为 ai-service 的 AiChatRequest（question / context / locale）。
     * 响应 data 含 {@code answer} 字段。{@code X-User-Id} 让 ai-service 能跑 RAG（按调用者记忆召回）。
     */
    @PostMapping("/api/v1/reconstruct/chat")
    ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> request,
                                          @RequestHeader(value = "X-User-Id", required = false) String userId);
}
