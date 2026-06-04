package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.ai.model.dto.AiChatRequest;
import com.mnemoscape.ai.service.ChatReasoner;
import com.mnemoscape.ai.service.ReActController;
import com.mnemoscape.common.dto.ApiResponse;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ReActController reactController;

    /** 共享调度器：在视觉前置阻塞 / 上游首字延迟期间发 SSE 注释帧 :keepalive，
     *  防止前端浏览器 / 反向代理 / 开发服务器把"无任何字节流出"的 SSE 当死连接 reset。
     *  daemon 线程 + 单例 — 不会延迟 ai-service 关闭。 */
    private final ScheduledExecutorService keepAliveExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-keepalive");
        t.setDaemon(true);
        return t;
    });

    /** P3-13 动态 plan：把 LLM 规划调用放在独立线程池，不阻塞主回答的首字延迟。
     *  生成完成后通过 SSE 的 plan_update 帧异步推到前端，前端 reducer 替换硬编码 plan。 */
    private final java.util.concurrent.ExecutorService planExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-dynamic-plan");
        t.setDaemon(true);
        return t;
    });

    public ChatController(ChatReasoner reasoner, ReActController reactController) {
        this.reasoner = reasoner;
        this.reactController = reactController;
    }

    @PreDestroy
    public void shutdown() {
        keepAliveExec.shutdownNow();
        planExec.shutdownNow();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> chatOnce(
            @Valid @RequestBody AiChatRequest request,
            jakarta.servlet.http.HttpServletRequest http) {
        // 通过网关 X-User-Id 头取 caller，让 reasoner 能跑强制 RAG（关键词召回当前用户记忆）
        String userId = http.getHeader("X-User-Id");
        Map<String, Object> body = buildPayload(request);
        body.put("answer", reasoner.generateAnswer(request, userId));
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping(value = "/stream",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request,
                                  jakarta.servlet.http.HttpServletRequest http) {
        String userId = http.getHeader("X-User-Id");
        SseEmitter emitter = new SseEmitter(120_000L); // 2 min
        stream(emitter, request, userId);
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
        // 多模态混合检索：让前端在消息泡里渲染"🔍 已分析 N 张图片"标签
        if (reasoner.hasImages(request)) {
            body.put("vision_used", true);
            body.put("vision_model", reasoner.getVisionModel());
            body.put("attachment_count", request.getImages().size());
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
    private void stream(SseEmitter emitter, AiChatRequest request, String userId) {
        String requestId = UUID.randomUUID().toString();
        try {
            // event: meta — intent + 硬编码 plan（首帧极快返回，前端立刻渲染骨架）
            Map<String, Object> meta = buildPayload(request);
            meta.put("requestId", requestId);
            emitter.send(SseEmitter.event().name("meta").data(meta));
        } catch (Exception e) {
            log.warn("[ChatController] failed to send meta: {}", e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
            return;
        }

        // ============ 任务 C4：ReAct 自主循环路径 ============
        // 客户端在 AiChatRequest.reAct=true 时显式启用 ReAct；前端 SSE 帧
        // 会从 token/done 扩展为 thought/tool_start/tool_end/token/done，
        // 由本方法第二段订阅 ReActController.onEvent 桥接。
        if (request.isReAct()) {
            streamReAct(emitter, request, userId, requestId);
            return;
        }

        // P3-13：PLAN 意图下并发跑一次 LLM 动态规划。完成后推 plan_update 帧覆盖前端硬编码 4 步。
        // 这样首字延迟保持极快（先发硬编码 plan），动态 plan 在后台 1-3s 内自然替换。
        ChatReasoner.Intent intent = reasoner.classify(request.getQuestion());
        if (intent == ChatReasoner.Intent.PLAN) {
            boolean zh = request.getLocale() == null || request.getLocale().startsWith("zh");
            planExec.submit(() -> {
                try {
                    java.util.List<String> dyn = reasoner.generateDynamicPlan(
                            request.getQuestion(), zh, userId);
                    if (dyn != null && !dyn.isEmpty()) {
                        emitter.send(SseEmitter.event().name("plan_update").data(Map.of(
                                "plan", dyn,
                                "source", "llm",
                                "requestId", requestId)));
                    }
                } catch (Exception e) {
                    log.debug("[ChatController] plan_update emit failed (client gone?): {}", e.getMessage());
                }
            });
        }

        // ---- SSE keepalive：每 5 秒发一次注释帧（":\n\n"），防止视觉前置阻塞期间
        //      浏览器 / 代理把死连接 reset。第一条 token 到达后立即取消。 ----
        AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
        ScheduledFuture<?> keepAlive = keepAliveExec.scheduleAtFixedRate(() -> {
            if (firstTokenSeen.get()) return;
            try {
                // SseEmitter.event().comment(...) 产出 "<text>\n\n"，浏览器会忽略但保活
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                // 客户端断了 / emitter complete 了，让定时器里的下一次自然 noop
            }
        }, 5, 5, TimeUnit.SECONDS);

        // 把 reactor Flux 订阅在它自己的弹性调度器上，不阻塞 servlet 线程
        // 同时把 RAG / vision 工序作为 ReAct "tool call" 推到 SSE，前端渲染齿轮 → ✓ 动效
        ChatReasoner.ToolEventListener tools = new ChatReasoner.ToolEventListener() {
            @Override public void onStart(String name, String label, Object input) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", name);
                body.put("label", label);
                body.put("input", input);
                try {
                    emitter.send(SseEmitter.event().name("tool_start").data(body));
                } catch (Exception e) {
                    log.debug("[ChatController] tool_start emit failed: {}", e.getMessage());
                }
            }
            @Override public void onEnd(String name, Object output) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", name);
                body.put("output", output);
                try {
                    emitter.send(SseEmitter.event().name("tool_end").data(body));
                } catch (Exception e) {
                    log.debug("[ChatController] tool_end emit failed: {}", e.getMessage());
                }
            }
        };

        Disposable[] holder = new Disposable[1];
        holder[0] = reasoner.streamAnswer(request, userId, tools)
                .doOnError(err -> sendError(emitter, err, requestId))
                .doFinally(sig -> {
                    keepAlive.cancel(false);
                })
                .subscribe(
                        chunk -> {
                            firstTokenSeen.set(true);
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
            keepAlive.cancel(false);
            if (holder[0] != null) holder[0].dispose();
            sendError(emitter, new AiUpstreamException(AiUpstreamException.Reason.TIMEOUT,
                    "SSE timeout"), requestId);
            try { emitter.complete(); } catch (Exception ignored) {}
        });
        emitter.onError(t -> {
            keepAlive.cancel(false);
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

    /**
     * 任务 C4：把 ReActController 的事件桥到 SSE。
     *
     * <p>事件映射：
     * <ul>
     *   <li>thought     → {@code event: thought} data={text}</li>
     *   <li>action_start→ {@code event: tool_start} data={name, input}</li>
     *   <li>observation → {@code event: tool_end} data={name, output}</li>
     *   <li>token       → {@code event: token} data=text</li>
     *   <li>done        → {@code event: done} data={ok,requestId}</li>
     *   <li>error       → {@code event: error} data={code, detail, requestId}</li>
     * </ul>
     */
    private void streamReAct(SseEmitter emitter, AiChatRequest request, String userId, String requestId) {
        AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
        ScheduledFuture<?> keepAlive = keepAliveExec.scheduleAtFixedRate(() -> {
            if (firstTokenSeen.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                // 客户端断 / emitter 已关，定时器自动 noop
            }
        }, 5, 5, TimeUnit.SECONDS);

        // ReActController 同步跑（内部用弹性线程），把事件一个一个 emit 到 SSE。
        ChatReasoner.EventListener listener = evt -> {
            if (evt == null) return;
            try {
                switch (evt.type == null ? "" : evt.type) {
                    case "thought": {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("text", evt.text);
                        emitter.send(SseEmitter.event().name("thought").data(body));
                        break;
                    }
                    case "action_start": {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("name", evt.toolName);
                        body.put("input", evt.input);
                        emitter.send(SseEmitter.event().name("tool_start").data(body));
                        break;
                    }
                    case "observation": {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("name", evt.toolName);
                        body.put("output", evt.output);
                        emitter.send(SseEmitter.event().name("tool_end").data(body));
                        break;
                    }
                    case "token": {
                        if (evt.text == null || evt.text.isEmpty()) break;
                        firstTokenSeen.set(true);
                        emitter.send(SseEmitter.event().name("token").data(evt.text));
                        break;
                    }
                    case "done": {
                        emitter.send(SseEmitter.event().name("done").data(
                                Map.of("ok", true, "requestId", requestId, "reason", evt.text == null ? "" : evt.text)));
                        try { emitter.complete(); } catch (Exception ignored) {}
                        break;
                    }
                    case "error": {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("code", "REACT_ERROR");
                        body.put("detail", evt.text);
                        body.put("requestId", requestId);
                        emitter.send(SseEmitter.event().name("error").data(body));
                        break;
                    }
                    default:
                        // 未识别事件：忽略
                        break;
                }
            } catch (Exception e) {
                log.debug("[ChatController] react SSE emit failed: {}", e.getMessage());
            }
        };

        // 在独立线程池跑 ReActController.run() ——
        // 它内部会阻塞订阅 ChatReasoner 的 Flux（最多 45s/turn × 6 轮），
        // 不能放在 servlet 线程里。
        planExec.submit(() -> {
            try {
                reactController.run(request, userId, requestId, listener);
            } catch (Exception e) {
                log.warn("[ChatController] ReAct run uncaught: {}", e.toString());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "REACT_UNCAUGHT");
                body.put("detail", e.getMessage());
                body.put("requestId", requestId);
                try {
                    emitter.send(SseEmitter.event().name("error").data(body));
                } catch (Exception ignored) {}
                try { emitter.complete(); } catch (Exception ignored) {}
            } finally {
                keepAlive.cancel(false);
            }
        });

        emitter.onTimeout(() -> {
            keepAlive.cancel(false);
            sendError(emitter, new AiUpstreamException(AiUpstreamException.Reason.TIMEOUT,
                    "SSE timeout"), requestId);
            try { emitter.complete(); } catch (Exception ignored) {}
        });
        emitter.onError(t -> {
            keepAlive.cancel(false);
        });
    }
}
