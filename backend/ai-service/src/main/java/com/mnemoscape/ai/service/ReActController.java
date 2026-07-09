package com.mnemoscape.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.ai.model.dto.AiChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 自主循环驱动器（任务 C3）。
 *
 * <p>读取 ChatReasoner.streamReActAnswer 返回的 token 流，按
 * {@link ChatReasoner#REACT_PROTOCOL_PROMPT} 约定的协议逐字符切出：
 * <pre>
 *   &lt;thought&gt;...&lt;/thought&gt;                       → 推 thought 事件
 *   &lt;action tool="..."&gt;{...}&lt;/action&gt;             → 推 action_start
 *   (工具执行结果)                                  → 推 observation（写入 history）
 *   &lt;action tool="final"&gt;{"answer":"..."}&lt;/action&gt; → 推 token + done
 * </pre>
 *
 * <p>最多 6 轮；任何异常都会用 {@code error} 事件兜住 + 必发 {@code done}，
 * 保证 ChatController 的 SSE 流不会半开半闭。
 *
 * <p>事件载荷统一是 {@link ChatReasoner.ReActEvent}，由 ChatController 负责
 * 映射到 SSE 帧。
 */
@Service
public class ReActController {

    private static final Logger log = LoggerFactory.getLogger(ReActController.class);

    /** 6 轮硬上限 —— 超过这个数说明模型陷入死循环；强制 final。 */
    private static final int MAX_TURNS = 6;

    /** 软上限：单个 thought 字符串长度（防模型不闭合标签时无限累加）。 */
    private static final int MAX_TAG_CONTENT = 8_000;

    private static final Pattern THOUGHT_RE = Pattern.compile("<thought>([\\s\\S]*?)</thought>");
    private static final Pattern ACTION_RE = Pattern.compile(
            "<action\\s*tool=\"([^\"]+)\"\\s*>([\\s\\S]+?)(?:</action>)?\\s*$");

    private final ChatReasoner reasoner;
    private final ToolRegistry toolRegistry;
    private final reactor.core.scheduler.Scheduler aiBlockingScheduler;
    private final ObjectMapper json = new ObjectMapper();

    public ReActController(ChatReasoner reasoner, ToolRegistry toolRegistry,
                           @org.springframework.beans.factory.annotation.Qualifier("aiBlockingScheduler")
                           reactor.core.scheduler.Scheduler aiBlockingScheduler) {
        this.reasoner = reasoner;
        this.toolRegistry = toolRegistry;
        this.aiBlockingScheduler = aiBlockingScheduler;
    }

    /**
     * 同步入口：跑完 6 轮（或更早的 final）后返回。把每轮事件通过 listener 推出去。
     * 用阻塞方式消费 Flux — 内部用弹性调度器，调用方通常在独立线程池里跑。
     */
    public void run(String userQuestion, String userId, String requestId,
                    ChatReasoner.EventListener listener) {
        AiChatRequest req = new AiChatRequest();
        req.setQuestion(userQuestion == null ? "" : userQuestion);
        run(req, userId, requestId, listener);
    }

    /** 重载：直接接受 {@link AiChatRequest}（让 ChatController 传完整请求体）。 */
    public void run(AiChatRequest req, String userId, String requestId,
                    ChatReasoner.EventListener listener) {
        if (listener == null) {
            log.warn("[ReActController] run called with null listener, no-op");
            return;
        }
        List<ChatReasoner.ReActTurn> history = new ArrayList<>();
        try {
            String userQuestion = req == null ? "" : (req.getQuestion() == null ? "" : req.getQuestion());
            String visionPrefix = "";
            if (reasoner.hasImages(req)) {
                // Run vision pre-pass to analyze the attached images
                visionPrefix = reasoner.buildVisionPrefix(req, ChatReasoner.NO_OP_TOOLS);
            }
            history.add(new ChatReasoner.ReActTurn("user", visionPrefix + userQuestion));

            for (int turn = 0; turn < MAX_TURNS; turn++) {
                String turnRaw = collectTurn(req, userId, history);

                // 先抓 thought（一定有，否则模型协议没遵守）
                String thought = extractFirst(turnRaw, THOUGHT_RE, 1);
                if (thought != null && !thought.isBlank()) {
                    emitThought(listener, requestId, thought.trim());
                }

                // 再抓 action
                String[] action = extractFirstAction(turnRaw);
                if (action == null) {
                    // 没有 action：当作纯文本 final 答案直接收尾
                    String text = stripAllTags(turnRaw);
                    if (!text.isBlank()) {
                        emitTokenChunks(listener, requestId, text);
                    }
                    emitDone(listener, requestId, "no-action");
                    return;
                }

                String toolName = action[0];
                String argsJson = action[1];

                if ("final".equals(toolName)) {
                    // 解析 argsJson.answer
                    String answer = extractFinalAnswer(argsJson);
                    if (!answer.isBlank()) {
                        emitTokenChunks(listener, requestId, answer);
                    }
                    emitDone(listener, requestId, "final-tool");
                    return;
                }

                // 普通工具：emit action_start → execute → emit observation
                Object input = safeParseArgs(argsJson);
                emitActionStart(listener, requestId, toolName, input);
                Object output;
                try {
                    output = toolRegistry.get(toolName)
                            .execute(argsJson, new ToolRegistry.ReActContext(userId, requestId));
                } catch (Exception e) {
                    output = java.util.Map.of("error", "tool execution failed",
                            "tool", toolName,
                            "detail", e.getClass().getSimpleName() + ": " + e.getMessage());
                    log.warn("[ReActController] tool {} failed: {}", toolName, e.toString());
                }
                String observationText;
                try {
                    observationText = json.writeValueAsString(output);
                } catch (Exception jsonErr) {
                    observationText = String.valueOf(output);
                }
                emitObservation(listener, requestId, toolName, output, observationText);

                // 记录到 history 给下一轮
                history.add(new ChatReasoner.ReActTurn("assistant", turnRaw.trim()));
                history.add(new ChatReasoner.ReActTurn("observation", observationText));
            }

            // 6 轮都没到 final：强制收尾
            emitError(listener, requestId, "ReAct 循环超过 " + MAX_TURNS + " 轮");
            emitDone(listener, requestId, "max-turns");
        } catch (Exception e) {
            log.warn("[ReActController] uncaught exception: {}", e.toString());
            emitError(listener, requestId, "ReAct 循环异常: " + e.getMessage());
            try { emitDone(listener, requestId, "error"); } catch (Exception ignored) {}
        }
    }

    /* ---------------- 内部：流式消费 + 累积 ---------------- */

    /**
     * 调 ChatReasoner 拿 token Flux，阻塞累积成完整一轮的字符串。
     * 用 {@link Flux#blockFirst}/collectList + toString 在订阅线程里 join 起来 —
     * ChatController 在独立线程池里调这里所以不会卡 servlet 线程。
     */
    private String collectTurn(AiChatRequest req, String userId, List<ChatReasoner.ReActTurn> history) {
        StringBuilder sb = new StringBuilder();
        try {
            Flux<ChatReasoner.ReActEvent> flux = reasoner.streamReActAnswer(
                    req, userId, history, ChatReasoner.NO_OP_TOOLS, null);
            // 在弹性线程上订阅（ChatReasoner 的 streamReActAnswer 内部已用
            // boundedElastic，但这里再保险一次，避免栈帧卡在调用线程）
            List<ChatReasoner.ReActEvent> events = flux
                    .subscribeOn(aiBlockingScheduler)
                    .collectList()
                    .block(java.time.Duration.ofSeconds(45));
            if (events != null) {
                for (ChatReasoner.ReActEvent e : events) {
                    if (e == null) continue;
                    if ("react-token".equals(e.type) || "token".equals(e.type)) {
                        if (e.text != null) sb.append(e.text);
                    } else if ("injection".equals(e.type) && e.text != null) {
                        sb.append(e.text);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[ReActController] streamReActAnswer failed: {}", e.toString());
            throw new RuntimeException("ReAct stream failed: " + e.getMessage(), e);
        }
        // 软截断
        if (sb.length() > MAX_TAG_CONTENT) {
            return sb.substring(0, MAX_TAG_CONTENT);
        }
        return sb.toString();
    }

    /* ---------------- 内部：标签解析 ---------------- */

    private static String extractFirst(String s, Pattern p, int group) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(group) : null;
    }

    /** 返回 [toolName, argsJson]；没有 action 时返回 null。 */
    private static String[] extractFirstAction(String s) {
        if (s == null) return null;
        Matcher m = ACTION_RE.matcher(s);
        if (!m.find()) return null;
        return new String[] { m.group(1), m.group(2) };
    }

    private static String extractFinalAnswer(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return "";
        try {
            ObjectMapper m = new ObjectMapper();
            java.util.Map<?, ?> map = m.readValue(argsJson, java.util.Map.class);
            Object ans = map == null ? null : map.get("answer");
            return ans == null ? "" : String.valueOf(ans);
        } catch (Exception e) {
            return "";
        }
    }

    private Object safeParseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return java.util.Map.of();
        try {
            return json.readValue(argsJson, new TypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            return argsJson;
        }
    }

    /** 移除所有 <thought> <action> 标签，保留 raw 文本 — 用来在没 action 时作为兜底答案。 */
    private static String stripAllTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<thought>[\\s\\S]*?</thought>", "")
                .replaceAll("<action[\\s\\S]*?</action>", "")
                .trim();
    }

    /* ---------------- 内部：事件发射 ---------------- */

    private static void emit(ChatReasoner.EventListener l, ChatReasoner.ReActEvent e) {
        try {
            l.onEvent(e);
        } catch (Exception ignore) {
            // listener 自身异常不能再让 ReAct 循环炸
        }
    }

    private void emitThought(ChatReasoner.EventListener l, String reqId, String text) {
        ChatReasoner.ReActEvent e = new ChatReasoner.ReActEvent();
        e.type = "thought";
        e.text = text;
        e.requestId = reqId;
        emit(l, e);
    }

    private void emitActionStart(ChatReasoner.EventListener l, String reqId,
                                 String toolName, Object input) {
        ChatReasoner.ReActEvent e = new ChatReasoner.ReActEvent();
        e.type = "action_start";
        e.toolName = toolName;
        e.input = input;
        e.requestId = reqId;
        emit(l, e);
    }

    private void emitObservation(ChatReasoner.EventListener l, String reqId,
                                 String toolName, Object output, String text) {
        ChatReasoner.ReActEvent e = new ChatReasoner.ReActEvent();
        e.type = "observation";
        e.toolName = toolName;
        e.output = output;
        e.text = text;
        e.requestId = reqId;
        emit(l, e);
    }

    private void emitTokenChunks(ChatReasoner.EventListener l, String reqId, String text) {
        // 按行/标点切成小块模拟 streaming；颗粒感比一次性整段更接近真实 LLM 体验
        String[] chunks = text.split("(?<=\\n|\\.|。|!|!|？|\\?|;|；)");
        Set<String> seen = new HashSet<>();
        for (String c : chunks) {
            if (c == null || c.isEmpty()) continue;
            if (c.length() > 200) c = c.substring(0, 200);
            ChatReasoner.ReActEvent e = new ChatReasoner.ReActEvent();
            e.type = "token";
            e.text = c;
            e.requestId = reqId;
            emit(l, e);
            seen.add(c);
            if (seen.size() > 256) break;
        }
    }

    private void emitDone(ChatReasoner.EventListener l, String reqId, String reason) {
        ChatReasoner.ReActEvent e = new ChatReasoner.ReActEvent();
        e.type = "done";
        e.text = reason;
        e.requestId = reqId;
        emit(l, e);
    }

    private void emitError(ChatReasoner.EventListener l, String reqId, String detail) {
        ChatReasoner.ReActEvent e = new ChatReasoner.ReActEvent();
        e.type = "error";
        e.text = detail;
        e.requestId = reqId;
        emit(l, e);
    }
}
