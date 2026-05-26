package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.ai.model.dto.AiChatRequest;
import com.mnemoscape.ai.service.ChatReasoner;
import com.mnemoscape.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI 对话 SSE 接口（v2 — 真实模型流式）。
 *
 * <p>关键改动：
 * <ul>
 *   <li>{@code POST /chat} 同步路径：调用 {@link ChatReasoner#generateAnswer}
 *       直接返回模型完整响应；上游不可用时由
 *       {@link com.mnemoscape.ai.exception.AiServiceExceptionHandler} 把
 *       {@link AiUpstreamException} 翻译成 502/503。</li>
 *   <li>{@code POST /chat/stream} 流式路径：把 {@link ChatReasoner#streamAnswer}
 *       返回的 Flux&lt;String&gt; 逐个 token 推到 SSE，<b>不再调用 Thread.sleep</b>。
 *       前端节奏完全由模型真实流速驱动。</li>
 *   <li>错误分支：流中途上游 5xx → 推一个 {@code event: error}（含 code/requestId），
 *       然后 complete。前端据此渲染明确的"AI 暂不可用"提示，
 *       不再让模板冒充 AI 回复。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/reconstruct/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatReasoner reasoner;

    public ChatController(ChatReasoner reasoner) {
        this.reasoner = reasoner;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> chatOnce(
            @Valid @RequestBody AiChatRequest request) {
        Map<String, Object> body = buildPayload(request);
        body.put("answer", reasoner.generateAnswer(request));
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping(value = "/stream",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 min
        stream(emitter, request);
        return emitter;
    }

    /* ------------------------------------------------------------------ */

    private Map<String, Object> buildPayload(AiChatRequest request) {
        Map<String, Object> body = new HashMap<>();
        boolean zh = request.getLocale() == null || request.getLocale().startsWith("zh");
        ChatReasoner.Intent intent = reasoner.classify(request.getQuestion());
        body.put("intent", intent.name().toLowerCase());
        body.put("traceId", UUID.randomUUID().toString());
        if (intent == ChatReasoner.Intent.PLAN) {
            body.put("plan", reasoner.buildPlan(request.getQuestion(), zh));
        }
        return body;
    }

    /**
     * 把 ChatClient 的 token Flux 桥接到 SSE。
     *
     * <p>事件序列（典型）：
     * <pre>
     *   event: meta        — { intent, plan, traceId }
     *   event: token       — "..."（多个）
     *   event: done        — { ok: true }
     * </pre>
     * 上游失败时：
     * <pre>
     *   event: error       — { code, reason, requestId, detail }
     *   stream complete
     * </pre>
     *
     * 注意：tool_start / tool_end 在当前 1.0.0-M4 ChatClient 流式 API 中尚未直接
     * 暴露 callback；保留事件名以便前端 reducer 不变；当模型确实触发 tool calling 时，
     * Spring AI 会在内部消化（多轮），最终输出仍然只是 token Flux。
     */
    private void stream(SseEmitter emitter, AiChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        try {
            // event: meta — intent + plan
            Map<String, Object> meta = buildPayload(request);
            meta.put("requestId", requestId);
            emitter.send(SseEmitter.event().name("meta").data(meta));
        } catch (Exception e) {
            log.warn("[ChatController] failed to send meta: {}", e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
            return;
        }

        // 把 reactor Flux 订阅在它自己的弹性调度器上，不阻塞 servlet 线程
        Disposable[] holder = new Disposable[1];
        holder[0] = reasoner.streamAnswer(request)
                .doOnError(err -> sendError(emitter, err, requestId))
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(chunk));
                            } catch (Exception e) {
                                log.warn("[ChatController] send token failed: {}", e.getMessage());
                                if (holder[0] != null) holder[0].dispose();
                                try { emitter.completeWithError(e); } catch (Exception ignored) {}
                            }
                        },
                        err -> {
                            // doOnError 已经发过 error 帧，这里只 complete
                            try { emitter.complete(); } catch (Exception ignored) {}
                        },
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(Map.of("ok", true, "requestId", requestId)));
                            } catch (Exception e) {
                                log.debug("[ChatController] send done failed (client gone?): {}", e.getMessage());
                            }
                            try { emitter.complete(); } catch (Exception ignored) {}
                        }
                );

        emitter.onTimeout(() -> {
            if (holder[0] != null) holder[0].dispose();
            sendError(emitter, new AiUpstreamException(AiUpstreamException.Reason.TIMEOUT,
                    "SSE timeout"), requestId);
            try { emitter.complete(); } catch (Exception ignored) {}
        });
        emitter.onError(t -> {
            if (holder[0] != null) holder[0].dispose();
        });
    }

    private void sendError(SseEmitter emitter, Throwable err, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "AI_UPSTREAM_UNAVAILABLE");
        body.put("requestId", requestId);
        if (err instanceof AiUpstreamException ux) {
            body.put("reason", ux.getReason().name());
            body.put("detail", ux.getMessage());
        } else {
            body.put("reason", "UNKNOWN");
            body.put("detail", err == null ? "unknown error" : err.getMessage());
        }
        try {
            emitter.send(SseEmitter.event().name("error").data(body));
        } catch (Exception e) {
            log.debug("[ChatController] failed to send error frame: {}", e.getMessage());
        }
    }
}
