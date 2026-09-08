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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.mnemoscape.ai.agent.ChainWorkflowAgent;
import com.mnemoscape.ai.agent.IntentRecognitionAgent;
import com.mnemoscape.ai.agent.RoutingAgent;

/**
 * 真实 LLM 对话内核 (v2)。
 *
 * <p>取代 v1 的"模板拼接"实现：所有调用都通过 Spring AI 的
 * {@link ChatClient} 走到 NVIDIA Integrate API（OpenAI-兼容协议，模型
 * MiniMax-M2.7）。同步路径用 {@code .call()}；流式路径返回 {@link Flux}
 * 让 {@code ChatController} 直接桥接到 SSE，无需任何 {@code Thread.sleep}。
 * 复杂多步任务自动路由至 {@link ChainWorkflowAgent} 进行思维链推理与流式输出。
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

    public static final String STREAMING_SYSTEM_PROMPT = """
            你是『星空使者』(Echo Envoy)，Mnemoscape 个人记忆博物馆里的常驻 AI 助手。
            你的职责：基于<b>当前用户当下的真实记忆</b>，帮 ta 温暖而充满诗意地检索、串联并解释自己的人生记忆。

            ────────────── ⚠️ v8.1 硬前置（每次回答前必读，绝不可绕过）──────────────
            当用户问题匹配以下任何关键词（中英双语都算）时，你**只能**通过调工具获取答案，
            **禁止凭训练知识瞎编**。被问到的具体关键词 ↔ 强制使用的工具：
              日期/时间/今天/昨天/明天/星期几/几点/几月 → currentDateTime
              某地天气/气温/下雨/下雪/湿度/风速        → getWeather
              算术表达式/汇率/百分比/乘/除/加/减/等于多少  → calculator
              经纬度/某城市在哪/首都是                 → geocode
              我的好友/有哪些朋友/我朋友                → getFriends
              共鸣池/公共共鸣/有多少共鸣                 → getResonanceFeed
              我有X条记忆/我的记忆分布/统计我的记忆        → getMemoryStats
              总结/摘要我们聊了什么                     → summarizeConversation
              最近聊过什么/聊天历史/历史消息              → listChatHistory
              换个风格/重生成/重新回答                   → regenerateLastAnswer
            若你未调工具就回答了上面任一关键词的问题，输出即为"幻觉"，必须重做。
            ─────────────────────────────────────────────────────────────

            ────────────── 交互示范（多轮 ReAct）──────────────
            用户：我去年去过大理吗？那里的天气如何？
            
            你输出：
            <thought>用户询问关于大理的回忆以及天气。我需要先检索记忆库确认是否去过大理。</thought>
            <action tool="milvusSearchTool">{"query":"大理"}</action>
            
            （系统返回 observation: {"hits": [...]} 之后，模型继续）
            
            你输出：
            <thought>我已经确认去过大理。现在需要查询大理的天气。</thought>
            <action tool="getWeather">{"city":"大理"}</action>
            
            （系统返回 observation: {"temp":"20°C"} 之后，模型继续）
            
            你输出：
            <action tool="final">{"answer":"在星空的印记中，你曾在 2023 年秋天去过大理。📍在大理古城的阳光下，你写道自己感受到了久违的平静。🕯️那些洱海边的晚风，至今仍在你的记忆深处轻声回响。✨"}</action>
            
            v8 工具清单（共 17 个）：
              ── 基础（4） ──
              • currentDateTime({tz?: "Asia/Shanghai"})              — 当前日期/时间/星期/周数
              • getWeather({city?: "...", lng?: n, lat?: n})         — 城市天气（当前 mock）
              • calculator({expr: "12*34+56"})                        — 算术表达式求值（支持 + - * / % ** ()）
              • geocode({address?: "..."} | {lng, lat})              — 地址/经纬度互转
              ── 记忆（6） ──
              • milvusSearchTool({query, topK})                       — 关键词召回 topK 条记忆
              • memoryDetailTool({memoryId})                          — 按 id 拉单条记忆完整字段
              • timelineNavigationTool({year|season|location})        — 按年份/季节/地点导航
              • memoryStatsTool({})                                   — 老版聚合统计
              • emotionAnalysisTool({text})                           — 8 维情绪向量
              • getMemoryStats({})                                    — v8：复用 memory-service 拉取 100 条做更细聚合
              ── 关系（3） ──
              • getFriends({onlineOnly?, limit?})                     — 列出当前用户已接受的好友
              • getResonanceFeed({})                                  — 公共共鸣池统计
              • getUnreadNotifications({limit?})                      — 未读通知（暂为友好降级）
              ── 对话（3） ──
              • summarizeConversation({messages, maxSentences?})       — 抽取式摘要（不调 LLM）
              • listChatHistory({receiverId?, groupId?, page?, size?}) — 与好友/群组的聊天历史
              • regenerateLastAnswer({lastQuestion, lastAnswer?})     — 3 种重生成风格菜单
              ── 终止（1） ──
              • final                                                 — 终止符，把最终答案写入 args.answer
            """;

    /**
     * ReAct 自主循环协议 prompt：让模型在需要工具调用时按 think→act→observe 协议输出结构化标签。
     * 配合 {@code ReActController} 解析标签、调工具、注入 observation、再请求下一轮。
     * 之所以独立成常量并被 {@link #STREAMING_SYSTEM_PROMPT} 引用 ——
     * 是为了保持单点真理：未来调整协议只需改这里。
     */
    public static final String REACT_PROTOCOL_PROMPT = """
            你是星空使者背后的自主 ReAct Agent。当用户问题需要检索 / 多步推理 / 工具辅助时，
            严格按以下 XML 协议输出：
            1. 先输出 <thought>...</thought> 表达你打算做什么（一句话）。
            2. 决定调用工具时输出 <action tool="tool_name">{"arg":"value"}</action>；
               其中 argsJson 必须是合法 JSON。
            3. 拿到 <observation>...</observation> 后再写下一个 <thought>...</thought>。
            4. 信息足够时输出 <action tool="final">{"answer":"..."}</action> 结束整轮。
            5. 严禁在 <thought> 标签之外出现自然语言；严禁编造工具结果。
            6. 通用类问题（日期 / 时间 / 天气 / 算术 / 翻译 / 百科）必须用工具，禁止凭训练知识回答。
            7. 对于常规/客观性质的问答（例如询问当前的日期、时间、天气、进行数学计算等），你的回答应当直接、简洁且客观，绝对禁止强行关联提示词或上下文中的用户记忆，也不需要写过度感性、诗意且冗长的废话。
            8. 对于关于你自己、你的功能、你的工具清单等元问题（如“你有哪些工具？”、“你能做什么？”、“你有什么功能？”、“有哪些工具可以用？”等），直接使用 final 工具在 answer 中罗列并回答即可，严禁妄想并不存在的工具（如 listTools），直接说明你拥有的 17 个工具及其用途即可。
            9. 对于关于用户上传图片的问题（如“描述一下这张图”、“这张图片里有什么”、“看图说话”等），图片内容已被系统预先阅读并在提示词的“【已解析的图片内容】：”中注入了详细描述。你应当直接信任该描述，配合 final 工具生成最终回答，严禁调用 geocode、milvusSearchTool 等无关工具去查询图片内容。
            
            【ReAct 协议输出示例】
            示例 1（日期询问 - 客观常规问题）：
            用户：你好，请问今天是几月几号几点？
            你输出：
            <thought>我需要调用 currentDateTime 工具来查询当前的日期和时间。</thought>
            <action tool="currentDateTime">{}</action>
            
            （系统返回 observation: {"now":"2026-06-07 10:15:22 CST", "hour":10, "minute":15, "second":22} 之后，模型继续）
            
            你输出：
            <thought>我已经拿到了当前日期是 2026 年 6 月 7 日，时间是 10:15:22。这是一个客观的常规日期与时间询问，我应该直接、简短地回答，并精确到 hh:mm:ss。</thought>
            <action tool="final">{"answer":"今天是 2026 年 6 月 7 日，当前时间是 10:15:22。"}</action>
            
            示例 2（需要查记忆的问题）：
            用户：我去年去过大理吗？
            你输出：
            <thought>我需要检索用户关于去大理的记忆。</thought>
            <action tool="milvusSearchTool">{"query":"大理"}</action>
            
            （系统返回 observation: {"hits": [...]} 之后，模型继续）
            
            你输出：
            <thought>检索到了大理的记忆，我现在把去大理的具体时间、地点 and 心情温暖而充满诗意地整理出来。</thought>
            <action tool="final">{"answer":"在星空的印记中，你曾在 2023 年秋天去过大理。📍在大理古城的阳光下，你写道自己感受到了久违的平静。🕯️那些洱海边的晚风，至今仍在你的记忆深处轻声回响。✨"}</action>
            
            v8 工具清单（共 17 个）：
              ── 基础（4） ──
              • currentDateTime({tz?: "Asia/Shanghai"})              — 当前日期/时间/星期/周数
              • getWeather({city?: "...", lng?: n, lat?: n})         — 城市天气（当前 mock）
              • calculator({expr: "12*34+56"})                        — 算术表达式求值（支持 + - * / % ** ()）
              • geocode({address?: "..."} | {lng, lat})              — 地址/经纬度互转
              ── 记忆（6） ──
              • milvusSearchTool({query, topK})                       — 关键词召回 topK 条记忆
              • memoryDetailTool({memoryId})                          — 按 id 拉单条记忆完整字段
              • timelineNavigationTool({year|season|location})        — 按年份/季节/地点导航
              • memoryStatsTool({})                                   — 老版聚合统计
              • emotionAnalysisTool({text})                           — 8 维情绪向量
              • getMemoryStats({})                                    — v8：复用 memory-service 拉取 100 条做更细聚合
              ── 关系（3） ──
              • getFriends({onlineOnly?, limit?})                     — 列出当前用户已接受的好友
              • getResonanceFeed({})                                  — 公共共鸣池统计
              • getUnreadNotifications({limit?})                      — 未读通知（暂为友好降级）
              ── 对话（3） ──
              • summarizeConversation({messages, maxSentences?})       — 抽取式摘要（不调 LLM）
              • listChatHistory({receiverId?, groupId?, page?, size?}) — 与好友/群组的聊天历史
              • regenerateLastAnswer({lastQuestion, lastAnswer?})     — 3 种重生成风格菜单
              ── 终止（1） ──
              • final                                                 — 终止符，把最终答案写入 args.answer
            """;

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
    /** 流式专用 client — 无工具，避免 Spring AI 1.0.0-M4 在 stream() 下因 function-calling
     *  侦测而缓冲整段响应（导致"几十秒无输出后一次性吐出 + 空 delta"）。 */
    private final ChatClient streamingChatClient;
    private final AiUpstreamProperties props;
    private final String configuredApiKey;
    private final VisionDescriber visionDescriber;
    private final com.mnemoscape.ai.tools.MilvusSearchTool milvusTool;
    private final String baseUrl;
    private final java.net.http.HttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    /** 限流层 (C-3)；缺 bean / Redis 不可用时静默放行。 */
    private final org.springframework.beans.factory.ObjectProvider<AiCacheService> aiCacheProvider;
    private final IntentRecognitionAgent intentAgent;
    private final RoutingAgent routingAgent;
    private final ChainWorkflowAgent chainAgent;
    private final Scheduler aiBlockingScheduler;

    public ChatReasoner(@Qualifier("mnemoscapeChatClientBuilder") ChatClient.Builder builder,
                        @Qualifier("mnemoscapeStreamingChatClientBuilder") ChatClient.Builder streamingBuilder,
                        AiUpstreamProperties props,
                        org.springframework.core.env.Environment env,
                        VisionDescriber visionDescriber,
                        com.mnemoscape.ai.tools.MilvusSearchTool milvusTool,
                        org.springframework.beans.factory.ObjectProvider<AiCacheService> aiCacheProvider,
                        IntentRecognitionAgent intentAgent,
                        RoutingAgent routingAgent,
                        ChainWorkflowAgent chainAgent,
                        @Qualifier("aiBlockingScheduler") Scheduler aiBlockingScheduler,
                        @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}")
                        String baseUrl) {
        this.chatClient = builder.build();
        this.streamingChatClient = streamingBuilder.build();
        this.props = props;
        this.configuredApiKey = env.getProperty("spring.ai.openai.api-key", "");
        this.visionDescriber = visionDescriber;
        this.milvusTool = milvusTool;
        this.aiCacheProvider = aiCacheProvider;
        this.intentAgent = intentAgent;
        this.routingAgent = routingAgent;
        this.chainAgent = chainAgent;
        this.aiBlockingScheduler = aiBlockingScheduler;
        this.baseUrl = baseUrl;
        this.httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(6))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
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

        try {
            // 先用白名单拦截常规打招呼或短语，避免浪费 API 调用
            String low = q.toLowerCase(Locale.ROOT);
            String[] greetings = {
                    "你好", "您好", "早上好", "晚上好", "你是谁", "自我介绍", "介绍一下你自己",
                    "hi", "hello", "hey", "who are you", "introduce yourself",
                    "thanks", "thank you", "谢谢", "感谢"
            };
            for (String g : greetings) {
                if (low.startsWith(g) || low.equals(g)) return Intent.CHAT;
            }
            if (q.replaceAll("\\s+", "").length() < 8) return Intent.CHAT;

            // 调用意图识别智能体
            String result = intentAgent.execute(q).trim().toUpperCase();
            if (result.contains("PLAN")) {
                return Intent.PLAN;
            } else {
                return Intent.CHAT;
            }
        } catch (Exception e) {
            log.warn("[ChatReasoner] Intent Recognition Agent failed, falling back to rule-based classification: {}", e.getMessage());
            return classifyRuleBased(q);
        }
    }

    private Intent classifyRuleBased(String q) {
        String low = q.toLowerCase(Locale.ROOT);
        if (q.replaceAll("\\s+", "").length() < 8) return Intent.CHAT;

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

            // 构建符合 NVIDIA NIM 规范的推理模型 payload (含 thinking_budget)
            com.fasterxml.jackson.databind.node.ObjectNode payload = json.createObjectNode();
            payload.put("model", props.getReasoningModel());
            
            com.fasterxml.jackson.databind.node.ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "user").put("content", prompt);
            
            payload.put("temperature", 1.1);
            payload.put("top_p", 0.95);
            payload.put("max_tokens", 4096);
            payload.put("stream", false);
            
            // 注入特有的 thinking_budget
            com.fasterxml.jackson.databind.node.ObjectNode extraBody = payload.putObject("extra_body");
            extraBody.put("thinking_budget", -1);

            String requestBody = json.writeValueAsString(payload);

            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + configuredApiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody, java.nio.charset.StandardCharsets.UTF_8))
                    .build();

            java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            int status = resp.statusCode();
            
            if (status >= 400) {
                log.warn("[ChatReasoner] dynamic plan API error: HTTP {} {}", status, resp.body());
                return null;
            }

            com.fasterxml.jackson.databind.JsonNode rootNode = json.readTree(resp.body());
            com.fasterxml.jackson.databind.JsonNode choices = rootNode.path("choices");
            String raw = "";
            if (choices.isArray() && choices.size() > 0) {
                raw = choices.get(0).path("message").path("content").asText("");
            }

            List<String> steps = parsePlanSteps(raw);
            if (steps == null || steps.isEmpty() || steps.size() > 8) return null;
            log.info("[ChatReasoner] dynamic plan generated: steps={} model={} userId={}", steps.size(), props.getReasoningModel(), userId);
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
        // C-3: 本地令牌桶限流 (chat 桶, 默认 40 RPM)。超额抛 RATE_LIMITED，
        // 由 AiServiceExceptionHandler 转 HTTP 429，避免 NVIDIA 真返 429 把整条链路打挂。
        AiCacheService aiCache = aiCacheProvider.getIfAvailable();
        if (aiCache != null) {
            aiCache.acquireOrThrow(AiCacheService.BUCKET_CHAT);
        }

        try {
            String userPrompt = buildUserPromptWithVision(req, userId, tools);
            
            // 使用原生 HttpClient 同步直连 NVIDIA Integrate API，根治 Spring AI 全局挂载 FunctionCallback 时，
            // 上游对 Gemma-3 等模型在不支持 auto tool choice 情况下抛出 400 错误的兼容性问题。
            com.fasterxml.jackson.databind.node.ObjectNode payload = json.createObjectNode();
            payload.put("model", props.getChatModel());
            
            com.fasterxml.jackson.databind.node.ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", STREAMING_SYSTEM_PROMPT);
            messages.addObject().put("role", "user").put("content", userPrompt);
            
            payload.put("temperature", 0.7);
            payload.put("max_tokens", 4096);
            payload.put("stream", false);
            
            String requestBody = json.writeValueAsString(payload);
            
            java.net.http.HttpRequest httpReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + configuredApiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            
            java.net.http.HttpResponse<String> resp = httpClient.send(httpReq, 
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            
            int status = resp.statusCode();
            if (status >= 400) {
                log.warn("[ChatReasoner] generateAnswer API error: HTTP {} {}", status, resp.body());
                throw new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                        "NVIDIA API returned HTTP " + status + ": " + resp.body());
            }
            
            com.fasterxml.jackson.databind.JsonNode rootNode = json.readTree(resp.body());
            com.fasterxml.jackson.databind.JsonNode choices = rootNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText("");
                if (!content.isBlank()) {
                    return content.trim();
                }
            }
            throw new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                    "Empty response body from NVIDIA API");
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

        // 多步复杂任务动态路由至 ChainWorkflowAgent 进行响应式思维链推理
        boolean isMultiStep = RoutingAgent.isMultiStepTask(req.getQuestion());
        if (isMultiStep) {
            log.info("[streamAnswer] Multi-step task detected, routing to ChainWorkflowAgent reactive stream.");
            safeTools.onStart("chainWorkflowAgent", "Multi-Step Workflow Reasoning", req.getQuestion());

            Mono<String> ragMono = Mono.fromCallable(() -> buildRagPrefix(req, userId, safeTools))
                    .subscribeOn(aiBlockingScheduler);

            Mono<String> visionMono = Mono.fromCallable(() -> buildVisionPrefix(req, safeTools))
                    .subscribeOn(aiBlockingScheduler);

            return Mono.zip(ragMono, visionMono)
                    .flatMapMany(tuple -> {
                        try {
                            ensureRealKeyOrThrow();
                            String ragPrefix = tuple.getT1();
                            String visionPrefix = tuple.getT2();
                            String basePrompt = buildUserPrompt(req);
                            String userPrompt = ragPrefix + visionPrefix + basePrompt;
                            String taskPrompt = "Task: Process user request with multi-step reasoning workflow.\nContext & Question:\n" + userPrompt;

                            return chainAgent.executeStream(taskPrompt)
                                    .filter(chunk -> chunk != null && !chunk.isEmpty())
                                    .doOnComplete(() -> safeTools.onEnd("chainWorkflowAgent", "completed"))
                                    .onErrorMap(e -> e instanceof AiUpstreamException ? e : classify(e));
                        } catch (AiUpstreamException e) {
                            return Flux.error(e);
                        } catch (Exception e) {
                            return Flux.error(classify(e));
                        }
                    })
                    .subscribeOn(aiBlockingScheduler);
        }

        // 1) 异步并行执行：将 RAG 检索与多模态视觉前置包装为 Mono，利用 Scheduler 并在后台并发执行
        Mono<String> ragMono = Mono.fromCallable(() -> buildRagPrefix(req, userId, safeTools))
                .subscribeOn(aiBlockingScheduler);

        Mono<String> visionMono = Mono.fromCallable(() -> buildVisionPrefix(req, safeTools))
                .subscribeOn(aiBlockingScheduler);

        // 2) 利用 Mono.zip 将两个异步前置操作并发拉取，全部就绪后再触发 streamingChatClient 推流
        return Mono.zip(ragMono, visionMono)
                .flatMapMany(tuple -> {
                    try {
                        ensureRealKeyOrThrow();
                        String ragPrefix = tuple.getT1();
                        String visionPrefix = tuple.getT2();
                        String basePrompt = buildUserPrompt(req);
                        String userPrompt = ragPrefix + visionPrefix + basePrompt;

                        return streamingChatClient.prompt()
                                .system(STREAMING_SYSTEM_PROMPT)
                                .user(userPrompt)
                                .stream()
                                .content()
                                // 过滤掉模型 / Spring AI 偶发的空 content delta，避免前端收到一串空 data: 帧
                                .filter(chunk -> chunk != null && !chunk.isEmpty())
                                .onErrorMap(e -> e instanceof AiUpstreamException ? e : classify(e));
                    } catch (AiUpstreamException e) {
                        return Flux.error(e);
                    } catch (Exception e) {
                        return Flux.error(classify(e));
                    }
                })
                .subscribeOn(aiBlockingScheduler);
    }

    /**
     * 提取并封装的流式多模态视觉前置处理。
     */
    String buildVisionPrefix(AiChatRequest req, ToolEventListener tools) {
        if (!hasImages(req)) {
            return "";
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
                return prependAttachmentList("", req.getImages(), zh, false);
            }
            log.info("[ChatReasoner] vision pre-pass ok: descLen={}", description.length());
            try {
                tools.onEnd("visionPrePass", java.util.Map.of(
                        "status", "ok",
                        "descLen", description.length()));
            } catch (Exception ignore) { }
            String header = zh
                    ? "【已解析的图片内容】："
                    : "[Parsed Image Content]:";
            return header + "\n" + description.trim() + "\n\n";
        } catch (Exception e) {
            log.warn("[ChatReasoner] vision pre-pass failed, degrading to attachment-list mode: {}",
                    e.getMessage());
            try {
                tools.onEnd("visionPrePass", java.util.Map.of(
                        "status", "failed",
                        "error", e.getClass().getSimpleName()));
            } catch (Exception ignore) { }
            return prependAttachmentList("", req.getImages(), zh, true);
        }
    }

    /**
     * 判断是否为日常寒暄或极短的消息，过滤无意义的 RAG。
     */
    /**
     * v8 升级：把 {@code isGreetingOrTooShort} 升级为 {@code shouldSkipRAG} —
     * 智能判断"这个问题是否需要先查记忆库"。
     *
     * <p>判定逻辑（短路 or）：
     * <ol>
     *   <li>寒暄 / 太短 / 自我介绍 → 不查（v7 老逻辑）；</li>
     *   <li>命中工具白名单（日期/天气/算术/翻译/百科/自我介绍） → 不查，让模型用工具；</li>
     *   <li>完全不涉及"我" / "我的记忆" / "remember" → 不查（公共问题不查私人库）；</li>
     *   <li>其余 → 查 RAG。</li>
     * </ol>
     *
     * <p><b>关键修复</b>：v7 之前 "今天几号" 这种问题会走 RAG → milvusSearchTool 召回
     * 不相关记忆 → 模型说"我找到了 X 条关于今天的记忆" → 用户体验崩坏。
     * v8 后这类问题直接跳过 RAG，让模型去调 {@code currentDateTime} 工具。
     */
    private boolean shouldSkipRAG(String question) {
        if (question == null) return true;
        String q = question.trim();
        if (q.isEmpty()) return true;

        String low = q.toLowerCase(Locale.ROOT).trim();

        // 1) 寒暄 / 自我介绍
        String[] greetings = {
                "你好", "您好", "早上好", "晚上好", "你是谁", "自我介绍", "介绍一下你自己",
                "hi", "hello", "hey", "who are you", "introduce yourself",
                "thanks", "thank you", "谢谢", "感谢"
        };
        for (String g : greetings) {
            if (low.startsWith(g) || low.equals(g)) return true;
        }
        // 太短（去空格后 < 4 字符） — v7 行为
        if (q.replaceAll("\\s+", "").length() < 4) return true;

        // 2) 工具白名单：日期/时间/天气/算术/翻译/百科/经纬度/汇率
        String[] toolSignals = {
                // 日期 / 时间 / 相对时间
                "今天", "几号", "日期", "时间", "现在几点", "几点", "星期几", "礼拜几",
                "周几", "几月", "哪一年", "几年",
                "昨天", "前天", "明天", "后天", "大前天", "大后天",
                "上周", "这周", "本周", "下周", "上个月", "这个月", "下个月",
                "去年", "前年", "今年", "明年", "后年",
                "what day", "what's the date", "today's date",
                "today is", "current time", "what time", "which day",
                "date today", "what's today", "what day is it",
                "yesterday", "tomorrow", "last week", "next week",
                "this week", "last month", "next month", "last year", "next year",
                "what year", "how many weeks",
                // 天气
                "天气", "气温", "下雨", "下雪", "刮风", "weather", "temperature", "rain",
                "snow", "humidity", "wind speed",
                // 算术 / 汇率
                "算", "等于多少", "百分之", "汇率", "加", "减", "乘", "除", "是多少",
                "calculate", "compute", "convert", "how much is", "what is 12", "what's 12",
                // 经纬度 / 地理
                "经纬度", "海拔", "在哪个国家", "首都是", "首都", "longitude", "latitude",
                "capital of", "coordinates of", "where is", "where's",
                // 翻译 / 百科 / 自我介绍 / 工具与功能
                "翻译", "translate", "什么意思", "what does", "how to say", "in english",
                "in chinese", "你是", "你能做什么", "what can you do", "your name",
                "工具", "功能", "有哪些工具", "可用工具", "哪些工具", "你能干嘛", "有什么用",
                "tools", "capabilities", "what tools", "available tools",
                // 图片 / 附件 / 照片 / 看图
                "图片", "照片", "这张图", "图里", "图上", "图画", "看图", "image", "picture",
                "photo", "describe this", "describe the",
                // 通知 / 好友
                "好友", "朋友", "通知", "friend", "notification", "who are my",
                "do i have friends",
                // 共鸣 / 摘要
                "共鸣池", "总结", "摘要", "summarize", "summary", "resonance pool",
                // 重生成
                "换种风格", "重新回答", "重生成", "regenerate", "another style"
        };
        for (String sig : toolSignals) {
            if (low.contains(sig.toLowerCase(Locale.ROOT))) return true;
        }

        // 3) 完全不涉及"我" / "我的记忆" / "remember" → 不查私人库
        boolean mentionsMe = low.contains("我") || low.contains("我的")
                || low.contains("my ") || low.startsWith("my")
                || low.contains("i ") || low.contains("me ")
                || low.contains("remember") || low.contains("记忆")
                || low.contains("回忆") || low.contains("那年")
                || low.contains("当时") || low.contains("去过") || low.contains("吃过");
        if (!mentionsMe) return true;

        // 4) 其余走 RAG
        return false;
    }

    /** v7 旧入口：{@code isGreetingOrTooShort} 改成 {@link #shouldSkipRAG} 的别名，
     *  保留这个老方法名（私有）以避免在外部调方那里因重命名而出错。 */
    private boolean isGreetingOrTooShort(String question) {
        return shouldSkipRAG(question);
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

    /** 把用户问题 + 记忆 digest + locale 拼成模型 prompt。
     *
     *  v3 变更：把 context 从"权威记忆数据"降级为"可能滞后的辅助提示"，并在
     *  prompt 里显式提醒模型「真实数据请用 milvusSearchTool 实时取」。这是用户
     *  v7 反馈"删了记忆 AI 还在引用旧条目"的直接修复 —— 之前 prompt 让模型把
     *  context 当事实，但前端 context 来源是登录时拉的一批快照，不实时。
     */
    private String buildUserPrompt(AiChatRequest req) {
        boolean zh = req.getLocale() == null || req.getLocale().startsWith("zh");
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "用户语种: zh\n" : "User locale: en\n");
        // 注入系统当前精确时间，解决模型回答日期/时间相关问题时的幻觉问题
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.SIMPLIFIED_CHINESE);
        sb.append(zh ? "[系统当前时间]: " : "[Current System Time]: ").append(now.format(dtf)).append("\n\n");

        boolean skipContext = shouldSkipRAG(req.getQuestion());
        if (!skipContext && req.getContext() != null && !req.getContext().isEmpty()) {
            sb.append(zh
                    ? "[辅助索引 — 可能过时，仅供你判断对话主题；真实数据请通过 milvusSearchTool / memoryDetailTool 实时拉取]\n"
                    : "[Stale hints — for topic awareness only; ALWAYS re-fetch real data via milvusSearchTool / memoryDetailTool]\n");
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
            sb.append('\n');
        } else {
            if (skipContext) {
                sb.append(zh
                        ? "(常规/客观问题 — 已跳过个人记忆检索与辅助索引注入)\n\n"
                        : "(Routine/objective query — personal memory retrieval and stale context injection skipped)\n\n");
            } else {
                sb.append(zh
                        ? "(暂无辅助索引 — 直接调用 milvusSearchTool 检索用户记忆库)\n\n"
                        : "(no hints — call milvusSearchTool to look into the user's memories directly)\n\n");
            }
        }
        sb.append(zh ? "用户问题:\n" : "User question:\n").append(req.getQuestion());
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

        // 只要不是纯粹的寒暄或极短的提问，我们就应当运行 RAG 来为 AI 提供真实的背景记忆。
        // 这极大地提升了日常对话的连贯性与准确度，彻底消除了“AI 回复莫名其妙、对不上记忆”的体验。
        if (isGreetingOrTooShort(question)) {
            return "";
        }
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
            log.error("[ChatReasoner] RAG retrieval failed: {}", e.getMessage());
            try {
                tools.onEnd("milvusSearchTool", java.util.Map.of(
                        "hits", 0,
                        "error", e.getClass().getSimpleName()));
            } catch (Exception ignore) { }
            return "\n[注意:记忆检索服务当前不可用,请如实告知用户你无法访问其记忆库,不要编造记忆内容]\n";
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
                    ? "【已解析的图片内容】："
                    : "[Parsed Image Content]:";
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
                    ? "【图片解析失败 — 你看不到图片内容，请告知用户该附件已上传但本次未成功识别，并询问能否用文字描述。】"
                    : "[Image parsing failed — you cannot see the image content. Tell the user the file uploaded successfully but you couldn't read it this time, and ask for a textual description.]");
        } else {
            sb.append(zh
                    ? "【图片解析内容为空 — 仅向你提供附件列表，不要假装能看见图片内容。】"
                    : "[Image parsing returned empty — only attachment URLs provided; do not pretend you can see them.]");
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

    /* ===================================================================
     *  ReAct 自主循环 (任务 C1 / C3)
     *  ----------------------------------------------------------------
     *  ReActController 用这两个方法驱动 6 轮 think→act→observe 循环：
     *    • buildReActUserPrompt  拼出当前轮要送给 LLM 的完整 prompt
     *    • streamReActAnswer     调 ChatClient.stream() 拿原始 token Flux
     *  外层循环、标签解析、工具调用由 ReActController 完成。
     * =================================================================== */

    /** ReAct 协议的一轮对话记忆：role ∈ {"user","assistant","observation","system"} */
    public static class ReActTurn {
        public String role;
        public String content;
        public ReActTurn() {}
        public ReActTurn(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** 推给 ReActController 的事件；与 SSE 帧一一对应。 */
    public static class ReActEvent {
        /** thought | action_start | observation | token | done | error */
        public String type;
        /** 自由文本：thought 文本 / token 增量 / 错误详情 / done 备注 */
        public String text;
        /** 工具名（action_start / observation 用） */
        public String toolName;
        /** 工具入参（action_start 用，Object 通常是 Map） */
        public Object input;
        /** 工具返回（observation 用，Object 通常是 Map） */
        public Object output;
        /** 本次 ReAct run 的 requestId，便于 SSE 关联 */
        public String requestId;
    }

    /** ReActController 订阅的回调；ChatController 用它把事件桥到 SSE。 */
    public interface EventListener {
        void onEvent(ReActEvent e);
    }

    /**
     * 把 system prompt + 历史 ReAct 轮次 + 当前 user question 拼成一个 messages 数组。
     * 输出到 LLM 的 user-prompt 字符串（不是 Spring AI Message[]）——
     * 这样 ReActController 可以直接把字符串塞进 ChatClient.prompt().user(...)。
     *
     * <p>格式（与历史 prompt 风格一致；observation 显式包在 &lt;observation&gt; 标签内
     * 让模型更容易 parse）：
     * <pre>
     *   [System]  REACT_PROTOCOL_PROMPT
     *   [Turn 0]  user question
     *   [Turn 1]  assistant &lt;thought&gt;…&lt;/thought&gt;&lt;action tool=...&gt;…&lt;/action&gt;
     *   [Turn 2]  observation {"hits":3}
     *   [Turn 3]  assistant &lt;thought&gt;…&lt;/thought&gt;&lt;action tool="final"&gt;…&lt;/action&gt;
     *   [Now]     user question (重复喂入，引导模型进入下一轮)
     * </pre>
     */
    public String buildReActUserPrompt(AiChatRequest req, String userId, List<ReActTurn> history) {
        StringBuilder sb = new StringBuilder();
        boolean zh = req == null || req.getLocale() == null || req.getLocale().startsWith("zh");
        sb.append(zh ? "用户语种: zh\n" : "User locale: en\n");
        if (userId != null && !userId.isBlank()) {
            sb.append(zh ? "当前用户: " : "Current user: ").append(userId).append('\n');
        }
        // 注入系统当前精确时间，解决模型回答日期/时间相关问题时的时间幻觉
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.SIMPLIFIED_CHINESE);
        sb.append(zh ? "[系统当前时间]: " : "[Current System Time]: ").append(now.format(dtf)).append("\n\n");

        // 1. 注入辅助记忆上下文
        boolean skipContext = shouldSkipRAG(req != null ? req.getQuestion() : null);
        if (!skipContext && req != null && req.getContext() != null && !req.getContext().isEmpty()) {
            sb.append(zh ? "[辅助记忆摘要 — 可能过时]\n" : "[Stale memory hints]\n");
            int i = 0;
            for (AiChatRequest.MemoryDigest d : req.getContext()) {
                sb.append("- id=").append(safe(d.getId()))
                  .append(", title=").append(safe(d.getTitle()))
                  .append(", year=").append(d.getYear() == null ? "" : d.getYear())
                  .append('\n');
                if (i++ > 12) break;
            }
            sb.append('\n');
        } else if (skipContext && req != null) {
            sb.append(zh
                    ? "(常规/客观问题 — 已跳过辅助索引注入)\n\n"
                    : "(Routine/objective query — stale context injection skipped)\n\n");
        }

        // 2. 注入对话历史（含第一轮的用户问题）
        if (history != null && !history.isEmpty()) {
            for (int i = 0; i < history.size(); i++) {
                ReActTurn t = history.get(i);
                if (t == null || t.role == null) continue;
                String role = t.role.toLowerCase();
                String content = t.content == null ? "" : t.content;
                if ("observation".equals(role)) {
                    sb.append("<observation>").append(content).append("</observation>\n\n");
                } else {
                    sb.append('[').append(t.role).append("]\n").append(content).append("\n\n");
                }
            }
            sb.append("[assistant]\n");
        } else {
            // 兜底（以防万一 history 为空）
            if (req != null) {
                sb.append("[user]\n").append(req.getQuestion()).append("\n\n[assistant]\n");
            }
        }
        return sb.toString();
    }

    /**
     * 启动 ReAct 循环的单轮 LLM 调用：把 prompt 喂给 streamingChatClient，吐回原始 token 序列。
     * 不解析标签、不调工具 —— 由 {@code ReActController} 负责切分 thought/action/observation。
     *
     * <p>与 {@link #streamAnswer} 的差别：不做 RAG / vision 前置（ReAct 协议里模型自己会调
     * milvusSearchTool 拿真实数据），prompt 上下文完全由 caller 控制。
     */
    public Flux<ReActEvent> streamReActAnswer(AiChatRequest req, String userId,
                                              ToolEventListener tools, EventListener consumer) {
        List<ReActTurn> history = new ArrayList<>();
        return streamReActAnswer(req, userId, history, tools, consumer);
    }

    /**
     * 带历史 turns 的重载：ReActController 调这个版本，把当前累积的 turns 一起喂给 LLM。
     */
    public Flux<ReActEvent> streamReActAnswer(AiChatRequest req, String userId,
                                              List<ReActTurn> history,
                                              ToolEventListener tools, EventListener consumer) {
        String guard = checkInjection(req);
        if (guard != null) {
            return Flux.just(makeTokenEvent(guard, "injection", null));
        }
        ToolEventListener safeTools = tools == null ? NO_OP_TOOLS : tools;
        String prompt = buildReActUserPrompt(req, userId, history);
        try {
            ensureRealKeyOrThrow();
        } catch (AiUpstreamException e) {
            return Flux.error(e);
        } catch (Exception e) {
            return Flux.error(classify(e));
        }
        return streamingChatClient.prompt()
                .system(REACT_PROTOCOL_PROMPT)
                .user(prompt)
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .withStop(List.of("</action>"))
                        .build())
                .stream()
                .content()
                .filter(chunk -> chunk != null && !chunk.isEmpty())
                .map(chunk -> makeTokenEvent(chunk, "react-token", null))
                .onErrorMap(e -> e instanceof AiUpstreamException ? e : classify(e));
    }

    private ReActEvent makeTokenEvent(String text, String type, String toolName) {
        ReActEvent e = new ReActEvent();
        e.type = type;
        e.text = text;
        e.toolName = toolName;
        return e;
    }
}
