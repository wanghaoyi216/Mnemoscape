package com.mnemoscape.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.ai.client.MemoryServiceClient;
import com.mnemoscape.ai.client.ResonanceServiceClient;
import com.mnemoscape.ai.tools.MilvusSearchTool;
import com.mnemoscape.common.dto.ApiResponse;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ReAct 工具注册表（任务 C2）。
 *
 * <p>把 6 个内置工具（milvusSearch / memoryStats / emotionAnalysis /
 * timelineNavigation / memoryDetail / final）注册到内存 {@code Map<String, Tool>}，
 * 由 {@link ReActController} 在解析模型输出的 {@code <action tool="...">} 时
 * 通过 {@link #get(String)} 取工具并执行。
 *
 * <p><b>设计取舍</b>：构造器用 {@code @Autowired(required = false)} 让缺依赖
 * （MilvusSearchTool / MemoryServiceClient / EmotionAnalysisTool）也不爆；
 * 缺的工具仍然会注册，但 {@code execute} 里 try/catch 后返回
 * {@code Map.of("error", "tool unavailable")}，让 ReAct 循环能继续跑并最终
 * 由 final 工具给用户一个温和的兜底答案。
 *
 * <p>之所以用"对象接口 + 名字"而不是 Spring AI 的 {@code FunctionCallback}：
 * ReAct 循环需要拿到原始 argsJson（不只 POJO）以塞进 observation；同时还要在
 * 一个事务里手动拼接 "工具 + observation" 的边界。FunctionCallback 抽象太厚。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 审计 logger —— 与 {@link com.mnemoscape.ai.tools.audit.ToolAuditAspect} 用同一个
     * logger 名 {@code ai-tool-audit}（logback 的 AI_TOOL_AUDIT appender，additivity=false），
     * 确保经本注册表的工具调用与注解路径的审计落同一文件 / 同一 ELK 索引。
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("ai-tool-audit");

    /* ---------------- 公共类型：工具契约 + 执行上下文 ---------------- */

    /**
     * ReAct 工具的统一接口。所有内置工具用匿名内部类实现 ——
     * 避免给每个工具单独建 .java 文件。
     */
    public interface Tool {
        String name();
        String description();
        /**
         * @param argsJson 模型输出的 &lt;action&gt;{}&lt;/action&gt; 内部 JSON
         * @param ctx      当前用户 / requestId 上下文（绝不接受模型伪造的 userId）
         * @return 任意对象；最终被 Jackson 序列化为 observation 字符串
         */
        Object execute(String argsJson, ReActContext ctx);
    }

    /** 工具执行上下文：userId 来自网关 X-User-Id，requestId 来自 ChatController。 */
    public static class ReActContext {
        public String userId;
        public String requestId;
        public ReActContext() {}
        public ReActContext(String userId, String requestId) {
            this.userId = userId;
            this.requestId = requestId;
        }
    }

    /** 工具查找失败（不是 RuntimeException 的子类继承，单独抛便于 caller 区分）。 */
    public static class ToolNotFoundException extends RuntimeException {
        public ToolNotFoundException(String name) {
            super("Tool not registered: " + name);
        }
    }

    /** 工具 args JSON 解析失败（模型把 JSON 写歪了）。 */
    public static class ToolArgsParseException extends RuntimeException {
        public ToolArgsParseException(String name, String argsJson, Throwable cause) {
            super("Failed to parse argsJson for tool '" + name + "': " + argsJson, cause);
        }
    }

    /* ---------------- Bean 依赖（允许缺） ---------------- */

    @Autowired(required = false)
    private MilvusSearchTool milvusSearchTool;

    @Autowired(required = false)
    private MemoryServiceClient memoryClient;

    @Autowired(required = false)
    private ResonanceServiceClient resonanceClient;

    /** v8 新增：AI 工具 getFriends / getUnreadNotifications 调 auth-service。 */
    @Autowired(required = false)
    private com.mnemoscape.ai.client.AuthServiceClient authClient;

    /** 情绪字典兜底：避免 EmotionAnalysisTool 不可用时 ReAct 路径死掉。 */
    @Autowired(required = false)
    private com.mnemoscape.ai.tools.EmotionAnalysisTool emotionAnalysisTool;

    private final ConcurrentMap<String, Tool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    /* ---------------- 注册入口 ---------------- */

    @PostConstruct
    public void registerBuiltinTools() {
        register(new MilvusSearchToolImpl());
        register(new MemoryStatsToolImpl());
        register(new EmotionAnalysisToolImpl());
        register(new TimelineNavigationToolImpl());
        register(new MemoryDetailToolImpl());
        register(new FinalToolImpl());

        // v8：注册 11 个新工具（4 大类 — 基础 / 项目内查询 / 对话辅助）。
        // 复用已注入的 Feign client，缺依赖时各工具自己 try/catch 返回 error，
        // 不会让 PostConstruct 失败（即使所有 client 都缺失，最多就是 11 个
        // "tool unavailable" 工具塞进注册表）。
        if (memoryClient != null || resonanceClient != null || authClient != null) {
            BuiltInToolsV2.BuiltInToolsRegistrar v8 =
                    new BuiltInToolsV2.BuiltInToolsRegistrar(memoryClient, resonanceClient, authClient);
            int added = 0;
            for (Tool t : v8.all()) {
                register(t);
                added++;
            }
            log.info("[ToolRegistry] v8 registered {} additional tools: {}",
                    added, v8.all().stream().map(Tool::name).toList());
        } else {
            log.warn("[ToolRegistry] v8 tools skipped — no Feign client present "
                    + "(memory/resonance/auth all null)");
        }
        log.info("[ToolRegistry] registered {} built-in tools: {}", tools.size(), tools.keySet());
    }

    /** 单测 / 第三方扩展用：把自己实现的 Tool 塞进注册表。 */
    public void register(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) return;
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        Tool t = tools.get(name);
        if (t == null) throw new ToolNotFoundException(name);
        return t;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(tools.keySet());
    }

    /* ---------------- 统一执行口（带审计） ---------------- */

    /**
     * 工具统一执行入口：查找 + 执行 + 结构化审计，一条龙。
     *
     * <p><b>为什么这里要补审计</b>：{@link com.mnemoscape.ai.tools.audit.ToolAuditAspect}
     * 靠 Spring AOP 拦截 {@code @Tool} 注解方法，但本注册表里的工具实现是匿名内部类
     * / lambda（捕获的是未代理目标对象），且大多根本没有 @Tool 注解 —— 切面对它们
     * 全部失效。此前只有 emotionAnalysisTool 底层 bean 的注解方法能触发切面审计，
     * 其余经 ReAct 主路径的工具调用完全不留痕。所有调用方应改走本方法而不是
     * {@link #get(String)} + {@code Tool#execute} 裸调。
     *
     * <p><b>已知重复审计</b>：emotionAnalysisTool 底层 bean 的 @Tool 方法仍会被切面
     * 记一次，经本方法再记一次 → 同一次调用产生两条审计记录。审计场景宁多勿缺，
     * 接受该重复；可用 {@code source} 字段区分两条记录来源。
     *
     * <p>字段与切面同格式（logger ai-tool-audit + StructuredArguments kv）：
     * toolName / userId / argsHash / resultHash / status / latencyMs /
     * timestamp / ts / errorClass / requestId / source。
     * 与切面一致不打 args/result 原值（入参含用户 prompt 切片，PII 风险），只记指纹；
     * userId 与既有代码一致取 ctx.userId（网关 X-User-Id 注入），拿不到记 anonymous。
     *
     * @throws ToolNotFoundException      工具未注册（与 get 语义一致，向上抛）
     * @throws ToolArgsParseException     argsJson 解析失败（工具内部抛出，向上抛）
     */
    public Object execute(String toolName, String argsJson, ReActContext ctx) {
        long startNanos = System.nanoTime();
        String userId = (ctx != null && ctx.userId != null && !ctx.userId.isBlank())
                ? ctx.userId : "anonymous";
        String argsHash = sha256(argsJson);

        Object result;
        // 预置 failure 兜底：若 try 内抛出非 RuntimeException（如 Error），
        // catch 不命中，finally 读取时也保证字段已初始化（definite assignment）
        String status = "failure";
        String resultHash = "exception";
        String errorClass = null;
        try {
            result = get(toolName).execute(argsJson, ctx);
            status = "success";
            resultHash = sha256(result == null ? null : String.valueOf(result));
            return result;
        } catch (RuntimeException e) {
            // 审计不能吞业务异常：记 failure 后原样上抛，语义与 ToolAuditAspect 一致
            status = "failure";
            resultHash = "exception";
            errorClass = e.getClass().getSimpleName();
            throw e;
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            AUDIT.info("ai-tool-call",
                    StructuredArguments.kv("toolName", toolName),
                    StructuredArguments.kv("userId", userId),
                    StructuredArguments.kv("argsHash", argsHash),
                    StructuredArguments.kv("resultHash", resultHash),
                    StructuredArguments.kv("status", status),
                    StructuredArguments.kv("timestamp", Instant.now().toString()),
                    StructuredArguments.kv("ts", System.currentTimeMillis()),
                    StructuredArguments.kv("latencyMs", latencyMs),
                    StructuredArguments.kv("errorClass", errorClass),
                    StructuredArguments.kv("requestId", ctx == null ? null : ctx.requestId),
                    StructuredArguments.kv("source", "tool-registry")
            );
        }
    }

    /** 取 SHA-256 十六进制并截断到 16 字符，格式与 ToolAuditAspect 保持一致。 */
    private static String sha256(String input) {
        if (input == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在任何合规 JDK 都存在；理论上不可能触发
            return "hash-error";
        }
    }

    /* ---------------- 6 个内置实现 ---------------- */

    /**
     * 1) milvusSearchTool — 调 MilvusSearchTool.searchForUser；
     *    args: {"query":"...","topK":5}
     */
    private class MilvusSearchToolImpl implements Tool {
        @Override public String name() { return "milvusSearchTool"; }
        @Override public String description() {
            return "Search the current user's authorized memories by keyword; "
                 + "returns up to topK matches with title/location/year/snippet/score.";
        }
        @Override
        public Object execute(String argsJson, ReActContext ctx)  {
            if (milvusSearchTool == null) {
                return Map.of("error", "tool unavailable", "tool", name(),
                        "reason", "MilvusSearchTool bean not present");
            }
            Map<String, Object> args = parseArgs(name(), argsJson);
            String query = str(args.get("query"));
            int topK = args.get("topK") instanceof Number n ? n.intValue() : 5;
            if (query.isBlank()) {
                return Map.of("error", "empty query");
            }
            MilvusSearchTool.Request mreq = new MilvusSearchTool.Request();
            mreq.query = query;
            mreq.topK = Math.max(1, Math.min(20, topK));
            MilvusSearchTool.Response resp = milvusSearchTool.searchForUser(mreq, ctx.userId);

            List<Map<String, Object>> summaries = new ArrayList<>();
            if (resp != null && resp.hits != null) {
                for (MilvusSearchTool.Hit h : resp.hits) {
                    summaries.add(MilvusSearchTool.hitToMap(h));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hits", summaries.size());
            out.put("summaries", summaries);
            out.put("degraded", resp == null ? true : resp.degraded);
            if (resp != null && resp.message != null) out.put("message", resp.message);
            return out;
        }
    }

    /**
     * 2) memoryStatsTool — 调 memoryClient.listMemories(0, 100) 算条数 + 情绪分布；
     *    args: {} (预留 topic 参数)
     */
    private class MemoryStatsToolImpl implements Tool {
        @Override public String name() { return "memoryStatsTool"; }
        @Override public String description() {
            return "Aggregate stats across the user's memory library: total count, "
                 + "privacy distribution, year histogram, top locations.";
        }
        @Override
        public Object execute(String argsJson, ReActContext ctx)  {
            if (memoryClient == null) {
                return Map.of("error", "tool unavailable", "tool", name(),
                        "reason", "MemoryServiceClient bean not present");
            }
            try {
                ApiResponse<Map<String, Object>> raw = memoryClient.listMemories(0, 100, ctx.userId);
                if (raw == null || raw.getData() == null) {
                    return Map.of("error", "memory-service returned empty");
                }
                Object items = raw.getData().get("items");
                int total = raw.getData().get("total") instanceof Number n ? n.intValue()
                        : (items instanceof List<?> list ? list.size() : 0);

                Map<String, Integer> privacy = new LinkedHashMap<>();
                int withCoords = 0;
                Map<String, Integer> yearHist = new LinkedHashMap<>();
                if (items instanceof List<?> list) {
                    for (Object row : list) {
                        if (!(row instanceof Map<?, ?> mapRaw)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) mapRaw;
                        String p = String.valueOf(m.getOrDefault("privacyLevel", "PRIVATE"))
                                .toUpperCase(Locale.ROOT);
                        privacy.merge(p, 1, Integer::sum);
                        Object lng = m.get("memoryLng");
                        Object lat = m.get("memoryLat");
                        if (lng instanceof Number && lat instanceof Number) withCoords++;
                        Object y = m.get("memoryYear");
                        if (y instanceof Number) {
                            yearHist.merge(String.valueOf(((Number) y).intValue()), 1, Integer::sum);
                        }
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("total", total);
                out.put("privacy", privacy);
                out.put("withCoords", withCoords);
                out.put("yearHistogram", yearHist);
                return out;
            } catch (Exception e) {
                return Map.of("error", "memory-service error", "detail", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 3) emotionAnalysisTool — 优先用 EmotionAnalysisTool，没有则用内置中英情绪字典；
     *    args: {"text":"..."}
     */
    private class EmotionAnalysisToolImpl implements Tool {
        @Override public String name() { return "emotionAnalysisTool"; }
        @Override public String description() {
            return "Analyze the emotional tone of text, returning 8-dim emotion vector + dominant emotion.";
        }
        @Override
        public Object execute(String argsJson, ReActContext ctx)  {
            Map<String, Object> args = parseArgs(name(), argsJson);
            String text = str(args.get("text"));
            if (text.isBlank()) {
                return Map.of("error", "empty text");
            }
            if (emotionAnalysisTool != null) {
                try {
                    com.mnemoscape.ai.tools.EmotionAnalysisTool.Request r =
                            new com.mnemoscape.ai.tools.EmotionAnalysisTool.Request();
                    r.text = text;
                    com.mnemoscape.ai.tools.EmotionAnalysisTool.Response resp =
                            emotionAnalysisTool.analyze(r);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("emotions", resp.emotions);
                    out.put("dominantEmotion", resp.dominantEmotion);
                    out.put("intensity", resp.intensity);
                    out.put("suggestedTone", resp.suggestedTone);
                    return out;
                } catch (Exception ignore) {
                    // 落回字典
                }
            }
            return fallbackDictionaryEmotion(text);
        }

        private Map<String, Object> fallbackDictionaryEmotion(String text) {
            String low = text.toLowerCase(Locale.ROOT);
            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("joy", scoreKw(low, "开心", "快乐", "高兴", "幸福", "happy", "joy"));
            scores.put("sadness", scoreKw(low, "难过", "伤心", "悲伤", "sad", "lost"));
            scores.put("nostalgia", scoreKw(low, "怀念", "想念", "回忆", "remember", "used to"));
            scores.put("excitement", scoreKw(low, "激动", "兴奋", "期待", "excited", "amazing"));
            scores.put("calm", scoreKw(low, "平静", "安宁", "舒适", "peaceful", "calm"));
            scores.put("melancholy", scoreKw(low, "惆怅", "感伤", "bittersweet", "wistful"));
            scores.put("gratitude", scoreKw(low, "感谢", "感恩", "grateful", "thankful"));
            scores.put("anxiety", scoreKw(low, "焦虑", "担心", "害怕", "worried", "anxious"));

            String dominant = "calm";
            double max = -1;
            for (var e : scores.entrySet()) {
                if (e.getValue() > max) { max = e.getValue(); dominant = e.getKey(); }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("emotions", scores);
            out.put("dominantEmotion", dominant);
            out.put("intensity", max);
            out.put("suggestedTone", dominantEmotionToTone(dominant));
            out.put("source", "dictionary");
            return out;
        }

        private double scoreKw(String text, String... kws) {
            int n = 0;
            for (String k : kws) if (text.contains(k)) n++;
            return Math.min(1.0, n * 0.3);
        }

        private String dominantEmotionToTone(String e) {
            return switch (e) {
                case "joy", "excitement" -> "warm_celebratory";
                case "sadness", "melancholy" -> "gentle_empathetic";
                case "nostalgia" -> "poetic_reflective";
                case "gratitude" -> "warm_affirming";
                case "anxiety" -> "calm_reassuring";
                default -> "gentle";
            };
        }
    }

    /**
     * 4) timelineNavigationTool — 接受 memoryIds 列表，按 id 拉详情，组装时间线；
     *    args: {"memoryIds": [...]}
     */
    private class TimelineNavigationToolImpl implements Tool {
        @Override public String name() { return "timelineNavigationTool"; }
        @Override public String description() {
            return "Given a list of memory ids, fetch details and assemble a chronological timeline.";
        }
        @Override
        public Object execute(String argsJson, ReActContext ctx)  {
            if (memoryClient == null) {
                return Map.of("error", "tool unavailable", "tool", name(),
                        "reason", "MemoryServiceClient bean not present");
            }
            Map<String, Object> args = parseArgs(name(), argsJson);
            Object idsRaw = args.get("memoryIds");
            List<String> ids = new ArrayList<>();
            if (idsRaw instanceof List<?> list) {
                for (Object o : list) if (o != null) ids.add(String.valueOf(o));
            } else if (idsRaw instanceof String s && !s.isBlank()) {
                ids.add(s);
            }
            if (ids.isEmpty()) {
                return Map.of("error", "memoryIds is empty");
            }
            List<Map<String, Object>> timeline = new ArrayList<>();
            for (String id : ids) {
                try {
                    ApiResponse<Map<String, Object>> raw = memoryClient.getMemory(id, ctx.userId);
                    if (raw == null || raw.getData() == null) continue;
                    Map<String, Object> m = raw.getData();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", str(m.get("id")));
                    entry.put("title", str(m.get("title")));
                    entry.put("location", str(m.get("memoryLocation")));
                    entry.put("year", m.get("memoryYear"));
                    entry.put("createdAt", str(m.get("createdAt")));
                    timeline.add(entry);
                } catch (Exception ignore) {
                    // 单条失败不阻塞整条时间线
                }
            }
            timeline.sort((a, b) -> {
                Integer yA = a.get("year") instanceof Number ? ((Number) a.get("year")).intValue() : 0;
                Integer yB = b.get("year") instanceof Number ? ((Number) b.get("year")).intValue() : 0;
                return Integer.compare(yA, yB);
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", timeline.size());
            out.put("timeline", timeline);
            return out;
        }
    }

    /**
     * 5) memoryDetailTool — 按 id 拉单条记忆完整字段；
     *    args: {"memoryId":"..."}
     */
    private class MemoryDetailToolImpl implements Tool {
        @Override public String name() { return "memoryDetailTool"; }
        @Override public String description() {
            return "Fetch the full detail of ONE specific memory by id.";
        }
        @Override
        public Object execute(String argsJson, ReActContext ctx)  {
            if (memoryClient == null) {
                return Map.of("error", "tool unavailable", "tool", name(),
                        "reason", "MemoryServiceClient bean not present");
            }
            Map<String, Object> args = parseArgs(name(), argsJson);
            String id = str(args.get("memoryId"));
            if (id.isBlank()) {
                return Map.of("error", "memoryId is required");
            }
            try {
                ApiResponse<Map<String, Object>> raw = memoryClient.getMemory(id, ctx.userId);
                if (raw == null || raw.getData() == null) {
                    return Map.of("error", "Memory not found or access denied");
                }
                return raw.getData();
            } catch (Exception e) {
                return Map.of("error", "memory-service error",
                        "detail", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 6) final — 特殊：调用它即表示 ReAct 循环结束；
     *    args: {"answer":"..."}
     *    返回 Map.of("answer", answer) —— ReActController 据此触发 done 事件。
     */
    private class FinalToolImpl implements Tool {
        @Override public String name() { return "final"; }
        @Override public String description() {
            return "Terminator. End the ReAct loop and emit the final answer to the user.";
        }
        @Override
        public Object execute(String argsJson, ReActContext ctx)  {
            Map<String, Object> args = parseArgs(name(), argsJson);
            String answer = str(args.get("answer"));
            // 即使 answer 为空也给个温和兜底，避免前端收到空消息
            if (answer.isBlank()) {
                answer = "（未能生成完整答案，请换个说法再问一次）";
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("answer", answer);
            return out;
        }
    }

    /* ---------------- 内部 helpers ---------------- */

    private Map<String, Object> parseArgs(String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ToolArgsParseException(toolName, argsJson, e);
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
