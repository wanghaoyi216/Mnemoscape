package com.mnemoscape.resonance.service;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.resonance.client.AiServiceClient;
import com.mnemoscape.resonance.model.entity.ChatMessage;
import com.mnemoscape.resonance.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 聊天室 AI 助手（设计书 §3.2.3）— 把社交聊天室和"星空使者"打通。
 *
 * <p>两个能力：
 * <ul>
 *   <li><b>群聊 {@code @AI} / {@code @Echo}</b>：用户在群里 @ 一下 AI，结合该群最近
 *       N 条消息上下文生成回复（{@link #answerInGroup}）。</li>
 *   <li><b>私聊破冰</b>：用户点"求助星空使者"按钮，AI 分析双方最近对话，给出一条
 *       破冰 / 话题引导建议（{@link #icebreakerForPrivate}）。</li>
 * </ul>
 *
 * <p><b>降级</b>：ai-service 不可用 / 缺 key 时返回一段诚实的中文兜底文案，
 * 绝不抛错打断聊天。AI 的身份固定为系统级用户 {@link #AI_USER_ID}。
 */
@Service
public class ChatAiAssistant {

    private static final Logger log = LoggerFactory.getLogger(ChatAiAssistant.class);

    /** AI 在聊天系统里的稳定身份（senderId）。前端据此渲染"星空使者"气泡。 */
    public static final String AI_USER_ID = "ai-echo-envoy";
    public static final String AI_DISPLAY_NAME = "星空使者";

    /** 触发群聊 AI 的前缀（大小写不敏感）。 */
    private static final String[] MENTION_PREFIXES = {"@ai", "@echo", "@星空使者", "@助手"};

    /** 喂给 AI 的最近聊天历史条数上限。 */
    private static final int HISTORY_LIMIT = 20;

    private final ChatMessageRepository chatMessageRepository;
    private final AiServiceClient aiClient;

    public ChatAiAssistant(ChatMessageRepository chatMessageRepository,
                           @Autowired(required = false) AiServiceClient aiClient) {
        this.chatMessageRepository = chatMessageRepository;
        this.aiClient = aiClient;
    }

    /** 判断一条群聊消息是否在召唤 AI。 */
    public boolean isAiMention(String content) {
        if (content == null) return false;
        String low = content.trim().toLowerCase(Locale.ROOT);
        for (String p : MENTION_PREFIXES) {
            if (low.startsWith(p)) return true;
        }
        return false;
    }

    /** 去掉 @AI 前缀，拿到用户真正想问的内容。 */
    public String stripMention(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        String low = trimmed.toLowerCase(Locale.ROOT);
        for (String p : MENTION_PREFIXES) {
            if (low.startsWith(p)) {
                return trimmed.substring(p.length()).trim();
            }
        }
        return trimmed;
    }

    /**
     * 群聊 @AI：结合该群最近 {@value #HISTORY_LIMIT} 条消息生成回复。
     *
     * @param groupId    群 id
     * @param askerId    发起 @AI 的用户 id（透传给 ai-service 让其能跑该用户的 RAG）
     * @param question   去掉 @ 前缀后的问题
     * @return AI 回复文本（已含降级兜底，永不为 null）
     */
    public String answerInGroup(String groupId, String askerId, String question) {
        List<ChatMessage> history = safeHistory(() ->
                chatMessageRepository.findByGroupIdOrderByCreatedAtAsc(groupId));
        String context = buildHistoryContext(history);
        String prompt = "你是群聊里的 AI 助手「星空使者」。下面是这个群聊最近的对话记录，"
                + "请结合上下文气氛，自然地回应被 @ 的问题。回答简洁友好，像群里的一员。\n\n"
                + "【最近对话】\n" + context + "\n\n"
                + "【被 @ 的问题】\n" + (question.isBlank() ? "（用户只是 @ 了你，请热情地打个招呼并询问能帮什么）" : question);
        return callAi(prompt, askerId,
                "星空使者暂时离线了，稍后再 @ 我吧～");
    }

    /**
     * 私聊破冰：分析双方最近对话，给出一条破冰 / 话题引导建议。
     *
     * @param userId    发起破冰的用户 id
     * @param otherId   对方用户 id
     * @return AI 破冰建议（已含降级兜底，永不为 null）
     */
    public String icebreakerForPrivate(String userId, String otherId) {
        List<ChatMessage> history = safeHistory(() ->
                chatMessageRepository.findPrivateMessages(userId, otherId));
        String context = buildHistoryContext(history);
        String prompt = "你是「星空使者」，一个温暖的破冰助手。两位用户在私聊中可能遇到了冷场或分歧，"
                + "请你作为中立的第三方，基于他们最近的对话，给出 1 条自然、有温度的破冰建议或可以聊的话题。"
                + "直接给出建议本身（可以是一句可以发出去的话 + 简短说明），不要长篇大论。\n\n"
                + "【最近对话】\n"
                + (context.isBlank() ? "（两人还没怎么聊，请给一个轻松的开场白建议）" : context);
        return callAi(prompt, userId,
                "试着问问对方最近有没有让 ta 印象深刻的回忆吧 —— 共同的记忆最容易拉近距离。");
    }

    /* ---------------- internal ---------------- */

    private String callAi(String prompt, String userId, String fallback) {
        if (aiClient == null) {
            log.warn("[ChatAiAssistant] ai-service client not available; returning fallback");
            return fallback;
        }
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("question", prompt);
            req.put("locale", "zh");
            ApiResponse<Map<String, Object>> resp = aiClient.chat(req, userId);
            Map<String, Object> data = resp == null ? null : resp.getData();
            Object answer = data == null ? null : data.get("answer");
            if (answer == null || String.valueOf(answer).isBlank()) {
                return fallback;
            }
            return String.valueOf(answer).trim();
        } catch (Exception e) {
            log.warn("[ChatAiAssistant] ai-service call failed: {}", e.toString());
            return fallback;
        }
    }

    /** 把最近 N 条消息渲染成"角色: 内容"的紧凑上下文。 */
    private String buildHistoryContext(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return "";
        int from = Math.max(0, history.size() - HISTORY_LIMIT);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            String who = AI_USER_ID.equals(m.getSenderId()) ? AI_DISPLAY_NAME
                    : ("用户-" + shortId(m.getSenderId()));
            String content = m.getContent() == null ? "" : m.getContent();
            if ("IMAGE".equalsIgnoreCase(m.getMessageType())) content = "[图片]";
            else if ("FILE".equalsIgnoreCase(m.getMessageType())) content = "[文件] " + safe(m.getFileName());
            sb.append(who).append("：").append(content).append('\n');
        }
        return sb.toString().trim();
    }

    private interface HistorySupplier { List<ChatMessage> get(); }

    private List<ChatMessage> safeHistory(HistorySupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("[ChatAiAssistant] failed to load chat history: {}", e.toString());
            return List.of();
        }
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) return "??";
        return id.length() <= 4 ? id : id.substring(0, 4);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
