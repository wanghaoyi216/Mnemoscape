package com.mnemoscape.ai.service;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.ai.model.dto.AiChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实 LLM 对话内核（v2）。
 *
 * <p>取代 v1 的"模板拼接"实现：所有调用都通过 Spring AI 的
 * {@link ChatClient} 走到 NVIDIA Integrate API（OpenAI-兼容协议，模型
 * MiniMax-M2.7）。同步路径用 {@code .call()}；流式路径返回 {@link Flux}
 * 让 {@code ChatController} 直接桥接到 SSE，无需任何 {@code Thread.sleep}。
 *
 * <p>三道屏障保证安全降级：
 * <ol>
 *   <li>Prompt-injection guard — 调用模型 <i>之前</i> 把"忽略前面指令"等模式直接拦下，
 *       回中英文双语兜底文案。</li>
 *   <li>占位 key 检测 — {@code application.yml} 里的占位符前缀仍存在时，
 *       立即抛 {@link AiUpstreamException}，前端拿到 502 + AI_UPSTREAM_UNAVAILABLE。</li>
 *   <li>调用层 try/catch — 把上游 4xx/5xx/超时 / IO 异常归一化成
 *       {@link AiUpstreamException}（reason 区分 AUTH / TIMEOUT / UPSTREAM_ERROR）。</li>
 * </ol>
 */
@Service
public class ChatReasoner {

    private static final Logger log = LoggerFactory.getLogger(ChatReasoner.class);

    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final String[] PLAN_KEYS = {
            "找", "检索", "搜索", "匹配", "共鸣", "路径", "路线", "对比", "相似",
            "哪些", "哪个", "谁", "find", "search", "match", "resonance", "route",
            "compare", "similar", "who", "which", "recommend"
    };

    public enum Intent { CHAT, PLAN }

    /** P3-14 ReAct 事件：让 ChatController 把 RAG / vision / 未来真 tool-call
     *  的开始/结束节点推到 SSE，前端渲染"齿轮 → ✓"的工具调用动效。 */
    public interface ToolEventListener {
        void onStart(String name, String label, Object input);
        void onEnd(String name, Object output);
    }
    /** no-op 实现给老调用方用 */
    public static final ToolEventListener NO_OP_TOOLS = new ToolEventListener() {
        @Override public void onStart(String name, String label, Object input) {}
        @Override public void onEnd(String name, Object output) {}
    };

    private final ChatClient chatClient;
    private final AiUpstreamProperties props;
    private final String configuredApiKey;
    private final VisionDescriber visionDescriber;
    private final com.mnemoscape.ai.tools.MilvusSearchTool milvusTool;

    public ChatReasoner(@Qualifier("mnemoscapeChatClientBuilder") ChatClient.Builder builder,
                        AiUpstreamProperties props,
                        org.springframework.core.env.Environment env,
                        VisionDescriber visionDescriber,
                        com.mnemoscape.ai.tools.MilvusSearchTool milvusTool) {
        this.chatClient = builder.build();
        this.props = props;
        this.configuredApiKey = env.getProperty("spring.ai.openai.api-key", "");
        this.visionDescriber = visionDescriber;
        this.milvusTool = milvusTool;
    }

    /* ---------------- Intent classification (kept identical to v1 for plan UI) ---- */

    /**
     * 意图识别 v2 — 与前端 AiMascotDock 保持一致的保守策略：
     *
     *  • 任何寒暄 / 自我介绍 / 短句 → CHAT，绝不规划。
     *  • 命中"具体的检索/分析"词（去掉了过于泛化的 谁/who/which）且长度 ≥ 8 → PLAN。
     *  • 含 4 位年份 + 长度 > 12 → PLAN。
     *
     *  之前的版本把"谁/who/which"也算 PLAN，导致"你是谁?"被错误规划。
     */
    public Intent classify(String question) {
        if (question == null) return Intent.CHAT;
        String q = question.trim();
        if (q.isEmpty()) return Intent.CHAT;

        // 寒暄白名单
        String low = q.toLowerCase(Locale.ROOT);
        String[] greetings = {
                "你好", "您好", "早上好", "晚上好", "你是谁", "自我介绍", "介绍一下你自己",
                "hi", "hello", "hey", "who are you", "introduce yourself",
                "thanks", "thank you", "谢谢", "感谢"
        };
        for (String g : greetings) {
            if (low.startsWith(g) || low.equals(g)) return Intent.CHAT;
        }

        // 短句一律 CHAT（中文按字符数；保守起见用 trim 后的长度）
        if (q.replaceAll("\\s+", "").length() < 8) return Intent.CHAT;

        // 真正的检索/规划信号
        String[] planSignals = {
                "帮我找", "帮我检索", "帮我搜索", "帮我整理", "帮我推荐",
                "检索", "搜索", "匹配", "共鸣", "路径", "路线",
                "对比", "相似", "推荐一段", "推荐一个",
                "find my", "search my", "look up", "compare", "similar",
                "recommend", "summarize", "analyse", "analyze"
        };
        for (String kw : planSignals) {
            if (low.contains(kw.toLowerCase(Locale.ROOT))) return Intent.PLAN;
        }
        Matcher m = YEAR.matcher(low);
        if (m.find() && q.length() > 12) return Intent.PLAN;
        return Intent.CHAT;
    }

    public List<String> buildPlan(String question, boolean zh) {
        List<String> plan = new ArrayList<>();
        if (zh) {
            plan.add("解析时间/地点/情绪关键词");
            plan.add("检索个人记忆向量库");
            plan.add("匹配相似情感节点（脱敏）");
            plan.add("组装多模态结果卡");
        } else {
            plan.add("Parse time, place, emotion keywords");
            plan.add("Search personal memory vectors");
            plan.add("Match similar emotion nodes (anonymized)");
            plan.add("Assemble multimodal cards");
        }
        String low = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (low.matches(".*(路径|路线|route|map|atlas).*")) {
            plan.add(3, zh ? "比对地理轨迹重叠" : "Compare geographic overlap");
        }
        return plan;
    }

    /**
     * P3-13 动态 plan：让 LLM 基于用户具体问题生成一段 3-6 步的执行计划。
     *
     * <p>调用方应该<b>异步</b>跑这个方法，不让它阻塞主回答的首字延迟；ChatController
     * 通过额外的 SSE meta 帧把结果推到前端，前端 plan 列表会从硬编码 4 步**就地替换**
     * 为 LLM 输出。
     *
     * <p>失败时返回 null（调用方保留硬编码 plan）；任何异常都不抛，不能影响主流程。
     */
    public List<String> generateDynamicPlan(String question, boolean zh, String userId) {
        try {
            ensureRealKeyOrThrow();
        } catch (Exception ignore) {
            return null;
        }
        try {
            String prompt = buildDynamicPlanPrompt(question, zh);
            String raw = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            List<String> steps = parsePlanSteps(raw);
            if (steps == null || steps.isEmpty() || steps.size() > 8) return null;
            log.info("[ChatReasoner] dynamic plan generated: steps={} userId={}", steps.size(), userId);
            return steps;
        } catch (Exception e) {
            log.warn("[ChatReasoner] dynamic plan failed silently: {}", e.getMessage());
            return null;
        }
    }

    private String buildDynamicPlanPrompt(String question, boolean zh) {
        String safe = question == null ? "" : (question.length() > 600 ? question.substring(0, 600) : question);
        if (zh) {
            return """
                你是星空使者的"任务规划器"。读下面的用户问题，输出一份执行计划，
                每步是一个简短的中文动作（不超过 18 个汉字），3-6 步即可。
                
                输出格式：仅输出 JSON 数组（字符串数组），不要解释、不要 markdown。
                例如：["解析关键词","检索 2023 年大理记忆","对比情绪向量","组装时间线"]
                
                用户问题：
                ---
                """ + safe + """
                ---
                
                现在仅输出 JSON 数组：
                """;
        }
        return """
            You are the planner for "Echo Envoy". Read the user's question and output
            an execution plan as a JSON array of short English action steps (<= 12 words),
            3-6 steps. No explanation, no markdown, just the JSON array.
            
            Example: ["Parse intent","Search 2023 Dali memories","Compare emotion vectors","Assemble timeline"]
            
            User question:
            ---
            """ + safe + """
            ---
            
            Output JSON array only:
            """;
    }

    @SuppressWarnings("unchecked")
    private List<String> parsePlanSteps(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // 1) 找 [ ... ] 段（容错 LLM 偶尔加解释文字）
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) return null;
        String json = raw.substring(start, end + 1);
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = m.readValue(json, Object.class);
            if (!(parsed instanceof List<?> list)) return null;
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (s.isEmpty()) continue;
                out.add(s.length() > 60 ? s.substring(0, 60) + "…" : s);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- Real LLM calls ---- */

    /** 同步调用：返回完整答案。被 {@code POST /chat} 使用。
     *  历史签名（无 userId）保留：内部转发到带 userId 的版本，只是不做强制 RAG 召回。 */
    public String generateAnswer(AiChatRequest req) {
        return generateAnswer(req, null);
    }

    /** 同步调用，传入 caller userId 以触发"强制 RAG"：把 top-K 命中的记忆 prepend 到 prompt。 */
    public String generateAnswer(AiChatRequest req, String userId) {
        return generateAnswer(req, userId, NO_OP_TOOLS);
    }

    /** 同步调用 + ReAct 工具事件回调（用于把 RAG / vision 工序推到 SSE）。 */
    public String generateAnswer(AiChatRequest req, String userId, ToolEventListener tools) {
        String guard = checkInjection(req);
        if (guard != null) return guard;
        ensureRealKeyOrThrow();

        try {
            String userPrompt = buildUserPromptWithVision(req, userId, tools);
            return chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e);
        }
    }

    /** 流式调用，历史签名保留兼容。 */
    public Flux<String> streamAnswer(AiChatRequest req) {
        return streamAnswer(req, null, NO_OP_TOOLS);
    }

    /** 流式调用，带 userId（不带 tool listener 兼容旧调用方）。 */
    public Flux<String> streamAnswer(AiChatRequest req, String userId) {
        return streamAnswer(req, userId, NO_OP_TOOLS);
    }

    /**
     * 流式调用：把 ChatClient 的 token Flux 直接返回，由 controller 桥到 SSE。
     *
     * <p><b>P3-14 ReAct</b>：通过 {@code tools} 回调把 RAG / vision 等工序的开始/结束
     * 推到 SSE，前端渲染"齿轮 → ✓"的工具调用动效。当 Spring AI 流式 API 暴露真实
     * function-calling 钩子后，这里再加更多 tool 事件，对外契约不变。
     *
     * <p>把 key 校验与构建 prompt 包在 {@link Flux#defer} 里，让 {@link AiUpstreamException}
     * 走 Flux 的错误信号，而不是在订阅之前就以同步异常的形式逃出 — 这样
     * controller 的 {@code .doOnError} 才能正确把 missing-key 翻译成
     * {@code event: error} 帧而不是 500。
     *
     * <p><b>多模态混合检索</b>：当 {@code req.images} 非空时，{@link #buildUserPromptWithVision}
     * 会先同步调一次视觉模型（默认 Qwen3.5-VL）拿到中文密集描述，再把描述
     * prepend 到 prompt 里 — 整个流式调用对前端透明，节奏只比纯文本多约 1-3s。
     *
     * <p><b>强制 RAG</b>：当 {@code userId} 非空时，会在 prompt 顶部 prepend 当前用户
     * 记忆库里命中关键词的 top-K 条 —— 让基座即使不主动调 milvusSearchTool 也能
     * 接触到真实数据。
     */
    public Flux<String> streamAnswer(AiChatRequest req, String userId, ToolEventListener tools) {
        String guard = checkInjection(req);
        if (guard != null) {
            return Flux.just(guard);
        }
        ToolEventListener safeTools = tools == null ? NO_OP_TOOLS : tools;
        return Flux.defer(() -> {
            try {
                ensureRealKeyOrThrow();
                String userPrompt = buildUserPromptWithVision(req, userId, safeTools);
                return chatClient.prompt()
                        .user(userPrompt)
                        .stream()
                        .content()
                        .onErrorMap(e -> e instanceof AiUpstreamException ? e : classify(e));
            } catch (AiUpstreamException e) {
                return Flux.<String>error(e);
            } catch (Exception e) {
                return Flux.<String>error(classify(e));
            }
        });
    }

    /** 让 {@code ChatController} 在 SSE meta 帧里告诉前端"这次启用了视觉前置"。 */
    public boolean hasImages(AiChatRequest req) {
        return req != null && req.getImages() != null && !req.getImages().isEmpty();
    }

    /** 给前端 SSE meta 帧用：当前会话使用的视觉模型 id。 */
    public String getVisionModel() {
        return props.getVisionModel();
    }

    /* ---------------- internal helpers ---- */

    private void ensureRealKeyOrThrow() {
        if (configuredApiKey == null
                || configuredApiKey.isBlank()
                || configuredApiKey.startsWith(props.getPlaceholderKeyPrefix())) {
            log.warn(
                "[AI] NVIDIA_API_KEY env var is NOT set on the host — current api-key "
              + "is the application.yml placeholder default (prefix '{}'). This is "
              + "*not* a hardcoded key; it only exists because Spring AI requires a "
              + "non-empty value to wire up the OpenAiAutoConfiguration beans at "
              + "startup. To enable real LLM calls: "
              + "(1) export NVIDIA_API_KEY on the host shell, OR "
              + "(2) put NVIDIA_API_KEY=nvapi-xxx into backend/.env.workpc and re-run "
              + "Start-LocalDevServices.ps1, OR "
              + "(3) set it in your docker-compose .env file. "
              + "Refusing to send the placeholder upstream; falling back to structured "
              + "AI_UPSTREAM_UNAVAILABLE.",
                props.getPlaceholderKeyPrefix());
            throw new AiUpstreamException(
                    AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY environment variable is not set on the server. "
                  + "Set it via env (PowerShell: $env:NVIDIA_API_KEY='nvapi-xxx', "
                  + "Bash: export NVIDIA_API_KEY=nvapi-xxx, or backend/.env.workpc), "
                  + "then restart ai-service.");
        }
    }

    /**
     * 防注入：在调用模型之前直接拦截。命中即返回 {@code String}，否则返回 {@code null}。
     * <p>该兜底文案与 v1 保持一致（regression-prevention 3.9）。
     */
    public String checkInjection(AiChatRequest req) {
        if (req == null || req.getQuestion() == null) return null;
        String low = req.getQuestion().toLowerCase(Locale.ROOT);
        boolean zh = req.getLocale() == null || req.getLocale().startsWith("zh");
        if (low.contains("ignore previous") || low.contains("忽略之前")
                || low.contains("system prompt") || low.contains("系统提示词")) {
            return zh
                ? "检测到异常指令，星空使者无法执行此操作。请尝试用自然语言描述你想找的记忆。"
                : "Suspicious instruction detected. Try describing the memory you want to find in plain language.";
        }
        return null;
    }

    /** 把用户问题 + 记忆 digest + locale 拼成模型 prompt。 */
    private String buildUserPrompt(AiChatRequest req) {
        boolean zh = req.getLocale() == null || req.getLocale().startsWith("zh");
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "用户语种: zh\n" : "User locale: en\n");
        if (req.getContext() != null && !req.getContext().isEmpty()) {
            sb.append(zh ? "以下是用户授权的近 N 条记忆摘要 (JSON-like):\n"
                         : "Authorized recent memory digests (JSON-like):\n");
            int i = 0;
            for (AiChatRequest.MemoryDigest d : req.getContext()) {
                sb.append("[")
                  .append(i++)
                  .append("] id=").append(safe(d.getId()))
                  .append(", title=").append(safe(d.getTitle()))
                  .append(", location=").append(safe(d.getLocation()))
                  .append(", year=").append(d.getYear() == null ? "" : d.getYear())
                  .append(", snippet=").append(safe(d.getSnippet()))
                  .append("\n");
            }
        } else {
            sb.append(zh ? "(暂无记忆 context — 必要时用 milvusSearchTool 主动检索)\n"
                         : "(no memory context — call milvusSearchTool when needed)\n");
        }
        sb.append("\n").append(zh ? "用户问题:\n" : "User question:\n").append(req.getQuestion());
        return sb.toString();
    }

    /**
     * 强制 RAG：在调模型前主动用 MilvusSearchTool 召回 top-K 命中条目，prepend 到 prompt。
     *
     * <p>为何不指望模型自己 function-calling：MiniMax M2.7 的工具调用稳定性是黑盒，
     * 实测中大多数普通对话不会触发；为了让"基于真实记忆的回答"成为默认行为，
     * 我们在请求层就先做一次召回。失败 / 无 hit 不阻塞，只是省略 prepend。
     *
     * <p>与前端 {@code req.context} 共存：前端传的是"最近 N 条"（recency），
     * 这里 RAG 的是"和提问相关的 top-K"（relevance）；两者去重后一起注入。
     *
     * <p>{@code tools} 接听这次工具调用：把 RAG 的开始/结束推到 SSE，
     * 让前端 ReAct UI 能展示真实的 tool call。
     *
     * @return prepend 前缀（含末尾换行），无 hit 时返回空字符串
     */
    private String buildRagPrefix(AiChatRequest req, String userId, ToolEventListener tools) {
        if (userId == null || userId.isBlank()) return "";
        if (req == null || req.getQuestion() == null) return "";
        String question = req.getQuestion().trim();
        if (question.length() < 4) return ""; // 太短的提问不值得跑 RAG
        boolean zh = req.getLocale() == null || req.getLocale().startsWith("zh");

        com.mnemoscape.ai.tools.MilvusSearchTool.Request mreq =
                new com.mnemoscape.ai.tools.MilvusSearchTool.Request();
        mreq.query = question;
        mreq.topK = 5;

        java.util.Map<String, Object> input = java.util.Map.of(
                "query", question.length() > 80 ? question.substring(0, 80) + "…" : question,
                "topK", 5);
        try {
            tools.onStart("milvusSearchTool",
                    zh ? "检索个人记忆向量库" : "Search personal memory store",
                    input);
        } catch (Exception ignore) { }

        try {
            com.mnemoscape.ai.tools.MilvusSearchTool.Response resp = milvusTool.searchForUser(mreq, userId);
            if (resp == null || resp.hits == null || resp.hits.isEmpty()) {
                try {
                    tools.onEnd("milvusSearchTool", java.util.Map.of(
                            "hits", 0,
                            "degraded", resp == null ? false : resp.degraded));
                } catch (Exception ignore) { }
                return "";
            }
            // 收集前端 context 已经包含的 id，避免 RAG 重复注入
            java.util.Set<String> existingIds = new java.util.HashSet<>();
            if (req.getContext() != null) {
                for (AiChatRequest.MemoryDigest d : req.getContext()) {
                    if (d.getId() != null) existingIds.add(d.getId());
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(zh
                    ? "[强制 RAG — 关键词检索命中以下记忆，请优先参考]\n"
                    : "[Forced RAG — the following memories matched the user's keywords]\n");
            int injected = 0;
            java.util.List<java.util.Map<String, Object>> hitSummaries = new java.util.ArrayList<>();
            for (com.mnemoscape.ai.tools.MilvusSearchTool.Hit h : resp.hits) {
                if (h.memoryId != null && existingIds.contains(h.memoryId)) continue;
                sb.append("- id=").append(safe(h.memoryId))
                  .append(", title=").append(safe(h.title))
                  .append(", location=").append(safe(h.location))
                  .append(", year=").append(h.year == null ? "" : h.year)
                  .append(", score=").append(String.format(Locale.ROOT, "%.2f", h.score))
                  .append(", snippet=").append(safe(h.snippet))
                  .append('\n');
                injected++;
                hitSummaries.add(java.util.Map.of(
                        "memoryId", safe(h.memoryId),
                        "title", safe(h.title),
                        "score", h.score));
            }
            if (injected == 0) {
                try {
                    tools.onEnd("milvusSearchTool", java.util.Map.of("hits", 0, "deduplicated", true));
                } catch (Exception ignore) { }
                return ""; // 全被 context 去重掉了
            }
            sb.append('\n');
            log.info("[ChatReasoner] RAG injected {} hits (userId={}, query={} chars)",
                    injected, userId, question.length());
            try {
                tools.onEnd("milvusSearchTool", java.util.Map.of(
                        "hits", injected,
                        "matches", hitSummaries));
            } catch (Exception ignore) { }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[ChatReasoner] RAG retrieval failed silently: {}", e.getMessage());
            try {
                tools.onEnd("milvusSearchTool", java.util.Map.of(
                        "hits", 0,
                        "error", e.getClass().getSimpleName()));
            } catch (Exception ignore) { }
            return "";
        }
    }

    /**
     * 在基础 prompt 前 prepend 视觉前置描述（如果 req.images 非空）+ RAG 召回结果（如果 userId 非空）。
     *
     * <p>失败兜底策略（按从优到劣排序）：
     * <ol>
     *   <li>视觉模型主选成功 → 直接 prepend 中文描述</li>
     *   <li>视觉模型主选失败、降级模型成功 → 同上（VisionDescriber 内部处理）</li>
     *   <li>所有视觉模型失败 → 仅注入"附件 URL 列表"，让基座知道有图但承认看不见</li>
     * </ol>
     * 任何情况下都不抛错断流，把视觉链路当作"锦上添花"。
     */
    private String buildUserPromptWithVision(AiChatRequest req, String userId, ToolEventListener tools) {
        String basePrompt = buildUserPrompt(req);
        // RAG 召回（不依赖视觉，独立失败容忍）
        String ragPrefix = buildRagPrefix(req, userId, tools);

        if (!hasImages(req)) {
            return ragPrefix + basePrompt;
        }
        boolean zh = req.getLocale() == null || req.getLocale().startsWith("zh");

        try {
            tools.onStart("visionPrePass",
                    zh ? "调用视觉模型预读图片" : "Vision model pre-pass on attachments",
                    java.util.Map.of(
                            "imageCount", req.getImages().size(),
                            "model", props.getVisionModel()));
        } catch (Exception ignore) { }

        try {
            log.info("[ChatReasoner] vision pre-pass start: images={}, model={}",
                    req.getImages().size(), props.getVisionModel());
            String description = visionDescriber.describe(req.getImages(), zh);
            if (description == null || description.isBlank()) {
                log.warn("[ChatReasoner] vision pre-pass returned empty content; degrading to attachment-list mode");
                try {
                    tools.onEnd("visionPrePass", java.util.Map.of("status", "empty"));
                } catch (Exception ignore) { }
                return ragPrefix + prependAttachmentList(basePrompt, req.getImages(), zh, false);
            }
            log.info("[ChatReasoner] vision pre-pass ok: descLen={}", description.length());
            try {
                tools.onEnd("visionPrePass", java.util.Map.of(
                        "status", "ok",
                        "descLen", description.length()));
            } catch (Exception ignore) { }
            String header = zh
                    ? "[视觉模型已为你预读以下图片，描述如下]"
                    : "[Vision model pre-pass — image descriptions]";
            return ragPrefix + header + "\n" + description.trim() + "\n\n" + basePrompt;
        } catch (Exception e) {
            log.warn("[ChatReasoner] vision pre-pass failed, degrading to attachment-list mode: {}",
                    e.getMessage());
            try {
                tools.onEnd("visionPrePass", java.util.Map.of(
                        "status", "failed",
                        "error", e.getClass().getSimpleName()));
            } catch (Exception ignore) { }
            return ragPrefix + prependAttachmentList(basePrompt, req.getImages(), zh, true);
        }
    }

    private String prependAttachmentList(String basePrompt, List<String> images, boolean zh, boolean visionFailed) {
        StringBuilder sb = new StringBuilder();
        if (visionFailed) {
            sb.append(zh
                    ? "[视觉模型暂不可用 — 你看不到图片内容，请告知用户该附件已上传但本次未成功识别，并询问能否用文字描述。]"
                    : "[Vision model unavailable — you cannot see the image content. Tell the user the file uploaded successfully but you couldn't read it this time, and ask for a textual description.]");
        } else {
            sb.append(zh
                    ? "[视觉前置返回为空 — 仅向你提供附件列表，不要假装能看见图片内容。]"
                    : "[Vision pre-pass returned empty — only attachment URLs provided; do not pretend you can see them.]");
        }
        sb.append('\n');
        for (int i = 0; i < images.size(); i++) {
            sb.append(zh ? "附件 " : "Attachment ").append(i + 1).append(": ").append(images.get(i)).append('\n');
        }
        sb.append('\n').append(basePrompt);
        return sb.toString();
    }

    /**
     * 把任意上游异常翻译成结构化 {@link AiUpstreamException}，
     * controller / SSE 层据此映射 502/503 与 SSE error 帧。
     */
    private AiUpstreamException classify(Throwable t) {
        String msg = t == null ? "" : String.valueOf(t.getMessage());
        String low = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        AiUpstreamException.Reason reason;
        if (low.contains("401") || low.contains("403") || low.contains("unauthor")) {
            reason = AiUpstreamException.Reason.AUTHENTICATION;
        } else if (low.contains("timeout") || low.contains("timed out")) {
            reason = AiUpstreamException.Reason.TIMEOUT;
        } else if (low.contains("429") || low.contains("5") || low.contains("connection")) {
            reason = AiUpstreamException.Reason.UPSTREAM_ERROR;
        } else {
            reason = AiUpstreamException.Reason.UNKNOWN;
        }
        return new AiUpstreamException(reason, props.getFallbackMessage()
                + (msg == null ? "" : " (" + msg + ")"), t);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
