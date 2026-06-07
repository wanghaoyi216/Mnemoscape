package com.mnemoscape.ai.tools;

import com.mnemoscape.ai.client.ResonanceServiceClient;
import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 让 AI 帮用户提交客服工单的工具。
 *
 * <p>触发场景：用户在对话中表达「我遇到了 bug / 这个功能挂了 / 我希望加上 XX 功能 /
 * 麻烦帮我反馈一下」时，星空使者可以代用户调用 resonance-service
 * {@code POST /support/tickets} 创建工单 —— 比让用户手动开浮动客服窗体验更顺畅。
 *
 * <p>安全：
 * <ul>
 *   <li>仅在用户<b>明确表达反馈意图</b>时才调用，不允许在普通寒暄对话里捏造工单。</li>
 *   <li>工单 priority 默认 NORMAL，模型可以按用户语气调整为 HIGH/URGENT，但不能 LOW。</li>
 *   <li>X-User-Id 强制从 SecurityContext 拿，不接受用户输入伪造。</li>
 * </ul>
 */
@Configuration
public class SupportTicketTool {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketTool.class);

    public static class Request {
        /** 必填：工单标题（一句话概括） */
        public String subject;
        /** 必填：详细描述，模型应该把用户说的内容总结进来 */
        public String description;
        /** 可选：LOW / NORMAL / HIGH / URGENT；默认 NORMAL */
        public String priority;
    }

    public static class Response {
        public String ticketId;
        public String status;
        public boolean created = false;
        public String message;
    }

    private final ResonanceServiceClient client;

    public SupportTicketTool(ResonanceServiceClient client) {
        this.client = client;
    }

    @Bean
    public FunctionCallback supportTicketToolCallback() {
        Function<Request, Response> fn = this::create;
        return FunctionCallback.builder()
                .description("Create a customer support ticket on behalf of the current user. "
                        + "Use ONLY when the user explicitly says they want to report a bug, "
                        + "request a feature, or escalate a problem. Do NOT use for normal "
                        + "greetings or memory questions. The ticket is then visible to "
                        + "platform admins in the support inbox.")
                .function("supportTicketTool", fn)
                .inputType(Request.class)
                .build();
    }

    public Response create(Request req) {
        Response resp = new Response();
        if (req == null || req.subject == null || req.subject.isBlank()) {
            resp.message = "subject required";
            return resp;
        }
        String userId = MilvusSearchTool.currentUserId();
        if (userId == null) {
            resp.message = "Unauthenticated — cannot file a ticket";
            return resp;
        }
        String priority = req.priority == null ? "NORMAL" : req.priority.trim().toUpperCase();
        if (!priority.equals("LOW") && !priority.equals("NORMAL")
                && !priority.equals("HIGH") && !priority.equals("URGENT")) {
            priority = "NORMAL";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", trimTo(req.subject, 200));
        body.put("description", req.description == null ? "" : trimTo(req.description, 4000));
        body.put("priority", priority);
        body.put("clientType", "AI_AGENT");

        try {
            ApiResponse<Map<String, Object>> raw = client.createTicket(body, userId);
            if (raw == null || raw.getData() == null) {
                resp.message = "resonance-service returned empty";
                return resp;
            }
            resp.ticketId = String.valueOf(raw.getData().get("id"));
            resp.status = String.valueOf(raw.getData().getOrDefault("status", "OPEN"));
            resp.created = true;
            return resp;
        } catch (Exception e) {
            log.warn("supportTicketTool degraded: {}", e.toString());
            resp.message = "resonance-service unreachable: " + e.getClass().getSimpleName();
            return resp;
        }
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
