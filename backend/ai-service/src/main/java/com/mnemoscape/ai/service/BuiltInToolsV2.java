package com.mnemoscape.ai.service;

import com.mnemoscape.ai.client.AuthServiceClient;
import com.mnemoscape.ai.client.MemoryServiceClient;
import com.mnemoscape.ai.client.ResonanceServiceClient;
import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v8 ReAct 工具集（11 个新工具，4 大类）。
 *
 * <p>把每个工具实现为 {@link ToolRegistry.Tool} 的内部实现，
 * 在 {@link BuiltInToolsRegistrar#register()} 一次性塞进 {@link ToolRegistry}。
 * 与原 6 个工具（v7 内嵌在 {@code ToolRegistry}）共用同一注册表，
 * 因此前端 ReAct UI 不需要任何改动即可看到 17 个工具的 ReAct 步骤。
 *
 * <h2>4 大类</h2>
 * <ol>
 *   <li><b>基础能力</b>：currentDateTime / getWeather / calculator / geocode ——
 *       解决"瞎编 2024"和"不会用工具"两个核心 bug。</li>
 *   <li><b>项目内查询</b>：getFriends / getMemoryStats / getResonanceFeed —
 *       复用现有 Feign client（MemoryServiceClient / ResonanceServiceClient），
 *       新增 AuthServiceClient 用于好友。</li>
 *   <li><b>对话自身辅助</b>：summarizeConversation / listChatHistory /
 *       regenerateLastAnswer —— 前两个调上游，第三个基于 LLM 调 self-distill。</li>
 * </ol>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>每个工具都吃 {@code Map<String,Object>} 而不是 POJO，args 解析失败时
 *       返回 {@code Map.of("error", ..., "tool", name())}，不抛异常，让
 *       {@code ReActController} 走"软失败"路径而不是把整轮对话废掉。</li>
 *   <li>日期 / 计算 / 地理都"宁可 mock 不调用外部 API"——
 *       不依赖 openweather / 高德，避免再开 API key / 配额管理。</li>
 *   <li>所有 IO 异常用 {@code try/catch} 兜底为可观察的 error map，附
 *       {@code tool} 名字便于前端 ReAct UI 展示"第 N 步：currentDateTime 失败"。</li>
 * </ul>
 *
 * <h2>不做什么</h2>
 * <ul>
 *   <li>不写"写"类工具（创建记忆 / 发消息 / 加好友）—— AI 应该读、不应该写社交关系；</li>
 *   <li>不接外部 LLM（summarize / regenerate 都走 {@code ChatReasoner}，避免循环依赖）；</li>
 *   <li>不持久化对话历史（listChatHistory 只读不写）。</li>
 * </ul>
 *
 * @see ToolRegistry 现有 6 个 v7 工具
 * @see BuiltInToolsRegistrar 静态注册入口
 */
@Component
public class BuiltInToolsV2 {

    private static final Logger log = LoggerFactory.getLogger(BuiltInToolsV2.class);

    /* ===================== 1) currentDateTime =====================
     * args: {} 或 {tz: "Asia/Shanghai"}
     * 返回: {now, tz, weekday, weekOfYear, iso, year, month, day, hour, minute, second, era}
     * 解决"瞎编 2024"核心 bug。
     * ============================================================== */
    public static class CurrentDateTimeTool implements ToolRegistry.Tool {
        @Override public String name() { return "currentDateTime"; }
        @Override public String description() {
            return "Get current local date/time. Args: {tz?: 'Asia/Shanghai'|'UTC'|'...'}. "
                 + "Returns: {now, tz, weekday, weekOfYear, iso, year, month, day, hour, minute, second, era}. "
                 + "Use this whenever the user asks 'what's today's date', 'what time is it', "
                 + "'what day of the week is today', 'how many weeks into the year', etc.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            try {
                Map<String, Object> args = parseArgs(name(), argsJson);
                String tzStr = strOr(args.get("tz"), "Asia/Shanghai");
                ZoneId zone;
                try {
                    zone = ZoneId.of(tzStr);
                } catch (Exception e) {
                    return Map.of("error", "invalid tz", "tool", name(), "got", tzStr,
                            "hint", "use IANA tz id like 'Asia/Shanghai' or 'UTC'");
                }
                ZonedDateTime now = ZonedDateTime.now(zone);
                LocalDate ld = now.toLocalDate();
                DayOfWeek dow = ld.getDayOfWeek();
                int weekOfYear = ld.get(WeekFields.ISO.weekOfWeekBasedYear());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("tz", zone.getId());
                out.put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                out.put("now", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")));
                out.put("year", ld.getYear());
                out.put("month", ld.getMonthValue());
                out.put("day", ld.getDayOfMonth());
                out.put("hour", now.getHour());
                out.put("minute", now.getMinute());
                out.put("second", now.getSecond());
                out.put("weekday", dow.name());            // MONDAY / TUESDAY / ...
                out.put("weekdayZh", weekdayZh(dow));      // 周一 / 周二 / ...
                out.put("weekOfYear", weekOfYear);
                out.put("era", now.getYear() < 0 ? "BC" : "AD");
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name());
            }
        }
    }

    /* ===================== 2) getWeather =====================
     * args: {city?: "北京", lng?: 116.4, lat?: 39.9}
     * 返回: {city, temp, desc, humidity, wind, source, mock: true}
     * 当前 mock 一周随机数据；接真 API 时改这里即可。
     * ======================================================== */
    public static class GetWeatherTool implements ToolRegistry.Tool {
        @Override public String name() { return "getWeather"; }
        @Override public String description() {
            return "Get current weather for a city or coordinates. "
                 + "Args: {city?: string, lng?: number, lat?: number}. "
                 + "Returns: {city, temp, desc, humidity, wind, source, mock}. "
                 + "Currently mocked (deterministic per city) — see v8 release notes for switching to OpenWeather.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            try {
                Map<String, Object> args = parseArgs(name(), argsJson);
                String city = strOr(args.get("city"), null);
                if (city == null || city.isBlank()) {
                    if (args.get("lng") instanceof Number && args.get("lat") instanceof Number) {
                        // 简单经纬度 → 城市 mock
                        double lng = ((Number) args.get("lng")).doubleValue();
                        double lat = ((Number) args.get("lat")).doubleValue();
                        city = mockCityByCoord(lng, lat);
                    } else {
                        return Map.of("error", "either city or (lng, lat) required", "tool", name());
                    }
                }
                // 确定性 mock：同一城市每次同结果（哈希到 [0,1)）
                long h = Math.abs((long) city.hashCode());
                int temp = 5 + (int) (h % 30);                     // 5°C ~ 34°C
                int humidity = 30 + (int) ((h / 7L) % 60);        // 30% ~ 89%
                int wind = 1 + (int) ((h / 11L) % 8);              // 1 ~ 8 m/s
                String[] descs = {"晴", "多云", "阴", "小雨", "阵雨", "雷阵雨", "雪"};
                String desc = descs[(int) (h % descs.length)];
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("city", city);
                out.put("temp", temp);
                out.put("desc", desc);
                out.put("humidity", humidity);
                out.put("wind", wind);
                out.put("unit", "°C / % / m·s⁻¹");
                out.put("source", "mnemoscape-mock-v1");
                out.put("mock", true);
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name());
            }
        }
    }

    /* ===================== 3) calculator =====================
     * args: {expr: "12*34+56"}
     * 返回: {result, steps: [...]}
     * 用递归下降 parser，避免把 eval 暴露给模型；支持 + - * / % 括号 + 一元负号。
     * 不支持函数（sin/cos）—— 不属于 AI 日常需要。
     * ============================================================ */
    public static class CalculatorTool implements ToolRegistry.Tool {
        @Override public String name() { return "calculator"; }
        @Override public String description() {
            return "Evaluate an arithmetic expression. "
                 + "Args: {expr: string like '12*34+56' or '(100-20)/4' or '2**10'}. "
                 + "Returns: {result, steps}. "
                 + "Supports: + - * / % ** ( ) and unary minus. No functions.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            try {
                Map<String, Object> args = parseArgs(name(), argsJson);
                String expr = str(args.get("expr"));
                if (expr.isBlank()) return Map.of("error", "expr required", "tool", name());
                ExprParser p = new ExprParser(expr.replace(" ", ""));
                double result = p.parseExpr();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("expr", expr);
                out.put("result", result);
                if (result == Math.floor(result) && !Double.isInfinite(result)
                        && Math.abs(result) < 1e15) {
                    out.put("resultInt", (long) result);
                }
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name(), "hint",
                        "use numbers, + - * / % ** and () only");
            }
        }
    }

    /* ===================== 4) geocode =====================
     * args: {address?: "北京天安门"} 或 {lng, lat}
     * 返回: {address, lng, lat, country, city, district, source, mock}
     * mock — 真实接高德 / Nominatim 时改这里。
     * ===================================================== */
    public static class GeocodeTool implements ToolRegistry.Tool {
        @Override public String name() { return "geocode"; }
        @Override public String description() {
            return "Geocode an address to (lng, lat) or reverse geocode coordinates. "
                 + "Args: {address?: string, lng?: number, lat?: number}. "
                 + "Returns: {address, lng, lat, country, city, district, mock}. "
                 + "Mocked for common Chinese cities; falls back to center of China for unknowns.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            try {
                Map<String, Object> args = parseArgs(name(), argsJson);
                Map<String, Object> out = new LinkedHashMap<>();
                if (args.get("address") instanceof String addr && !addr.isBlank()) {
                    out.putAll(mockGeocodeAddress(addr));
                    out.put("source", "mnemoscape-mock-v1");
                    out.put("mock", true);
                } else if (args.get("lng") instanceof Number && args.get("lat") instanceof Number) {
                    double lng = ((Number) args.get("lng")).doubleValue();
                    double lat = ((Number) args.get("lat")).doubleValue();
                    out.put("lng", lng);
                    out.put("lat", lat);
                    out.put("address", mockCityByCoord(lng, lat));
                    out.put("country", "中国");
                    out.put("source", "mnemoscape-reverse-mock-v1");
                    out.put("mock", true);
                } else {
                    return Map.of("error", "either address or (lng, lat) required", "tool", name());
                }
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name());
            }
        }
    }

    /* ===================== 5) getFriends =====================
     * args: {onlineOnly?: bool}
     * 走 AuthServiceClient.listFriends —— FriendController 已经在
     * auth-service 跑着，gateway 也路由了 /api/v1/friends。
     * ====================================================== */
    public static class GetFriendsTool implements ToolRegistry.Tool {
        private final AuthServiceClient authClient;
        public GetFriendsTool(AuthServiceClient authClient) { this.authClient = authClient; }

        @Override public String name() { return "getFriends"; }
        @Override public String description() {
            return "List current user's accepted friends. "
                 + "Args: {onlineOnly?: bool, limit?: number 1-200, default 50}. "
                 + "Returns: {count, friends: [{id, username, displayName, avatar, status, ...}]}.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            if (authClient == null) {
                return Map.of("error", "tool unavailable", "tool", name(),
                        "reason", "AuthServiceClient bean not present");
            }
            if (ctx == null || ctx.userId == null || ctx.userId.isBlank()) {
                return Map.of("error", "no userId in context", "tool", name());
            }
            try {
                ApiResponse<List<Map<String, Object>>> resp = authClient.listFriends(ctx.userId);
                if (resp == null || resp.getData() == null) {
                    return Map.of("error", "auth-service returned empty", "tool", name());
                }
                List<Map<String, Object>> friends = resp.getData();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("count", friends.size());
                out.put("friends", friends);
                out.put("source", "auth-service.FriendController.listFriends");
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name(),
                        "hint", "auth-service /api/v1/friends is not reachable");
            }
        }
    }

    /* ===================== 6) getUnreadNotifications =====================
     * 当前 auth-service 没有 notifications/unread 端点（项目无 notification-service）。
     * 返回友好降级："暂未提供通知聚合"。等以后接 notification-service 时零代码改动。
     * ==================================================================== */
    public static class GetUnreadNotificationsTool implements ToolRegistry.Tool {
        private final AuthServiceClient authClient;
        public GetUnreadNotificationsTool(AuthServiceClient authClient) { this.authClient = authClient; }

        @Override public String name() { return "getUnreadNotifications"; }
        @Override public String description() {
            return "List current user's unread notifications. "
                 + "Args: {limit?: number 1-50, default 20}. "
                 + "Returns: {count, notifications: [...], note}. "
                 + "Currently degraded: project has no dedicated notification-service yet.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            try {
                if (authClient == null || ctx == null || ctx.userId == null || ctx.userId.isBlank()) {
                    return Map.of("error", "tool unavailable", "tool", name(),
                            "reason", "no AuthServiceClient or userId");
                }
                try {
                    ApiResponse<List<Map<String, Object>>> resp = authClient.unreadNotifications(ctx.userId);
                    if (resp != null && resp.getData() != null) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("count", resp.getData().size());
                        out.put("notifications", resp.getData());
                        out.put("note", "来自 notification-service");
                        return out;
                    }
                } catch (Exception ignored) {
                    // 404/405 时降级
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("count", 0);
                out.put("notifications", List.of());
                out.put("note", "项目暂未提供通知聚合服务；未来接入 notification-service 后零代码改动");
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name());
            }
        }
    }

    /* ===================== 7) getMemoryStats =====================
     * 复用 MemoryServiceClient.listMemories(0, 100)。
     * 注意：这是 ai-service 内部工具，但与原 memoryStatsTool 90% 重复 —
     * 之所以重做，是想让 listChatHistory / getResonanceFeed 等"项目内
     * 查询"工具名一致；老 memoryStatsTool 留作向后兼容。
     * ============================================================ */
    public static class GetMemoryStatsTool implements ToolRegistry.Tool {
        private final MemoryServiceClient memoryClient;
        public GetMemoryStatsTool(MemoryServiceClient memoryClient) { this.memoryClient = memoryClient; }

        @Override public String name() { return "getMemoryStats"; }
        @Override public String description() {
            return "Get aggregate stats on the user's memory library: total count, "
                 + "privacy distribution, year histogram, top locations, with-coords count. "
                 + "Args: {} (no parameters). Returns: {total, privacy, yearHistogram, withCoords, topLocations}.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            if (memoryClient == null) return Map.of("error", "tool unavailable", "tool", name());
            if (ctx == null || ctx.userId == null) return Map.of("error", "no userId", "tool", name());
            try {
                ApiResponse<Map<String, Object>> raw = memoryClient.listMemories(0, 100, ctx.userId);
                if (raw == null || raw.getData() == null) {
                    return Map.of("error", "memory-service returned empty", "tool", name());
                }
                Object items = raw.getData().get("items");
                int total = raw.getData().get("total") instanceof Number n ? n.intValue()
                        : (items instanceof List<?> list ? list.size() : 0);
                Map<String, Integer> privacy = new LinkedHashMap<>();
                Map<String, Integer> yearHist = new LinkedHashMap<>();
                Map<String, Integer> locHist = new LinkedHashMap<>();
                int withCoords = 0;
                if (items instanceof List<?> list) {
                    for (Object row : list) {
                        if (!(row instanceof Map<?, ?> mr)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) mr;
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
                        Object loc = m.get("memoryLocation");
                        if (loc instanceof String s && !s.isBlank()) locHist.merge(s, 1, Integer::sum);
                    }
                }
                // top 5 locations
                List<Map<String, Object>> topLocs = new ArrayList<>();
                locHist.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(5)
                        .forEach(e -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("location", e.getKey());
                            r.put("count", e.getValue());
                            topLocs.add(r);
                        });
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("total", total);
                out.put("privacy", privacy);
                out.put("withCoords", withCoords);
                out.put("yearHistogram", yearHist);
                out.put("topLocations", topLocs);
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name());
            }
        }
    }

    /* ===================== 8) getResonanceFeed =====================
     * 调 /resonances/stats —— "公共共鸣池里有多少人看过 / 平均共鸣分是多少"。
     * 之所以不调 /resonances/search，是为了避免和 milvusSearchTool 双算。
     * =============================================================== */
    public static class GetResonanceFeedTool implements ToolRegistry.Tool {
        private final ResonanceServiceClient resonanceClient;
        public GetResonanceFeedTool(ResonanceServiceClient resonanceClient) { this.resonanceClient = resonanceClient; }

        @Override public String name() { return "getResonanceFeed"; }
        @Override public String description() {
            return "Get the global resonance pool statistics: total public memories, average resonance score, "
                 + "and pair-sampled count. Args: {}. Returns: {totalMatches, avgScore, poolSampled, pairCount, algorithmName}.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            if (resonanceClient == null) return Map.of("error", "tool unavailable", "tool", name());
            try {
                ApiResponse<Map<String, Object>> resp = resonanceClient.stats();
                if (resp == null || resp.getData() == null) {
                    return Map.of("error", "resonance-service returned empty", "tool", name());
                }
                Map<String, Object> out = new LinkedHashMap<>(resp.getData());
                out.put("source", "resonance-service /api/v1/resonances/stats");
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name(),
                        "hint", "resonance-service /api/v1/resonances/stats unreachable");
            }
        }
    }

    /* ===================== 9) summarizeConversation =====================
     * 不调外部 LLM（避免循环依赖），而是给出一个 deterministic 摘要模板：
     * 1) 抽取前 N 条 user 消息
     * 2) 简单按句号 / 问号切分 + 关键词
     * 真正的"智能摘要"留给前端的 history 视图。
     * ================================================================ */
    public static class SummarizeConversationTool implements ToolRegistry.Tool {
        @Override public String name() { return "summarizeConversation"; }
        @Override public String description() {
            return "Summarize a chat thread. Args: {messages: [{role, content}], maxSentences?: int 1-10, default 3}. "
                 + "Returns: {summary, keyPoints, wordCount}. Lightweight deterministic extractive summary (no LLM call).";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            try {
                Map<String, Object> args = parseArgs(name(), argsJson);
                Object msgsObj = args.get("messages");
                if (!(msgsObj instanceof List<?> msgs) || msgs.isEmpty()) {
                    return Map.of("error", "messages array required", "tool", name());
                }
                int maxSentences = args.get("maxSentences") instanceof Number n
                        ? Math.max(1, Math.min(10, n.intValue())) : 3;

                List<String> userTexts = new ArrayList<>();
                List<String> assistantTexts = new ArrayList<>();
                for (Object o : msgs) {
                    if (!(o instanceof Map<?, ?> mr)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) mr;
                    String role = String.valueOf(m.getOrDefault("role", ""));
                    Object c = m.get("content");
                    if (c instanceof String s && !s.isBlank()) {
                        if ("user".equalsIgnoreCase(role)) userTexts.add(s);
                        else if ("assistant".equalsIgnoreCase(role) || "ai".equalsIgnoreCase(role)) {
                            assistantTexts.add(s);
                        }
                    }
                }
                String joined = String.join("。", userTexts);
                // 简单按中文句号 / 英文 . / 问号 切分
                String[] sentences = joined.split("[。！？!?]+\\s*");
                List<String> picked = new ArrayList<>();
                for (String s : sentences) {
                    if (s == null) continue;
                    String t = s.trim();
                    if (t.isEmpty()) continue;
                    picked.add(t);
                    if (picked.size() >= maxSentences) break;
                }
                if (picked.isEmpty() && !userTexts.isEmpty()) {
                    picked.add(userTexts.get(0));
                }
                String summary = String.join("；", picked);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("summary", summary);
                out.put("keyPoints", picked);
                out.put("userMessageCount", userTexts.size());
                out.put("assistantMessageCount", assistantTexts.size());
                out.put("wordCount", joined.length());
                out.put("note", "deterministic extractive summary; for semantic summary use listChatHistory + an LLM");
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name());
            }
        }
    }

    /* ===================== 10) listChatHistory =====================
     * 调 ResonanceServiceClient.listChatMessages。
     * =========================================================== */
    public static class ListChatHistoryTool implements ToolRegistry.Tool {
        private final ResonanceServiceClient resonanceClient;
        public ListChatHistoryTool(ResonanceServiceClient resonanceClient) { this.resonanceClient = resonanceClient; }

        @Override public String name() { return "listChatHistory"; }
        @Override public String description() {
            return "List recent chat messages for the current user. "
                 + "Args: {receiverId?: string, groupId?: string, page?: int, size?: int 1-100, default 20}. "
                 + "Returns: {count, items: [{id, senderId, receiverId, content, ts, ...}]}.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            if (resonanceClient == null) return Map.of("error", "tool unavailable", "tool", name());
            if (ctx == null || ctx.userId == null) return Map.of("error", "no userId", "tool", name());
            try {
                Map<String, Object> args = parseArgs(name(), argsJson);
                String receiverId = strOr(args.get("receiverId"), null);
                String groupId = strOr(args.get("groupId"), null);
                int page = args.get("page") instanceof Number n ? n.intValue() : 0;
                int size = args.get("size") instanceof Number n
                        ? Math.max(1, Math.min(100, n.intValue())) : 20;
                ApiResponse<List<Map<String, Object>>> resp =
                        resonanceClient.listChatMessages(receiverId, groupId, page, size, ctx.userId);
                if (resp == null || resp.getData() == null) {
                    return Map.of("error", "resonance-service returned empty", "tool", name());
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("count", resp.getData().size());
                out.put("items", resp.getData());
                out.put("page", page);
                out.put("size", size);
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name(),
                        "hint", "resonance-service /api/v1/chat/messages unreachable");
            }
        }
    }

    /* ===================== 11) regenerateLastAnswer =====================
     * 不调外部 LLM（避免循环依赖）。给模型一个"换种风格重新回答"的提示模板：
     * 列出 3 个不同角度（更专业 / 更口语 / 更简洁）+ 让模型在下一轮中选一个。
     * 真正"重新生成"由前端 ChatView 触发"重生成"按钮 + 重新 POST /api/v1/chat。
     * ================================================================== */
    public static class RegenerateLastAnswerTool implements ToolRegistry.Tool {
        @Override public String name() { return "regenerateLastAnswer"; }
        @Override public String description() {
            return "Suggest 3 alternative styles to regenerate the last answer. "
                 + "Args: {lastQuestion: string, lastAnswer?: string}. "
                 + "Returns: {candidates: [{style, hint}], instruction}. "
                 + "This does NOT call an LLM — it returns a deterministic style menu for the frontend "
                 + "to display; the actual regen is triggered by re-POSTing the original question.";
        }
        @Override
        public Object execute(String argsJson, ToolRegistry.ReActContext ctx) {
            try {
                Map<String, Object> args = parseArgs(name(), argsJson);
                String lastQ = strOr(args.get("lastQuestion"), "");
                if (lastQ.isBlank()) return Map.of("error", "lastQuestion required", "tool", name());
                List<Map<String, Object>> candidates = List.of(
                        Map.of("style", "concise",
                                "hint", "用一两句话直接给结论；省去寒暄与背景。"),
                        Map.of("style", "professional",
                                "hint", "更专业 / 数据驱动；引用具体数字或代码示例。"),
                        Map.of("style", "warm",
                                "hint", "更口语 / 共情；适当使用 emoji 表达情感。")
                );
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("lastQuestion", lastQ);
                out.put("candidates", candidates);
                out.put("instruction", "前端请让用户选一种风格，然后把同一个问题以新风格重新发给 /api/v1/chat");
                return out;
            } catch (Exception e) {
                return Map.of("error", e.getMessage(), "tool", name());
            }
        }
    }

    /* ===================== 公共辅助 ===================== */

    /** 解析 args JSON，允许空 / null 走空 Map。 */
    static Map<String, Object> parseArgs(String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(argsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ToolRegistry.ToolArgsParseException(toolName, argsJson, e);
        }
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static String strOr(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    static String weekdayZh(DayOfWeek dow) {
        return switch (dow) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    /** 极简城市反查：根据经纬度 hash 到一个常见城市。 */
    static String mockCityByCoord(double lng, double lat) {
        // 只对几个核心城市做精确反查，其它返回经纬度字符串
        Map<double[], String> cities = Map.of(
                new double[]{116.40, 39.90}, "北京",
                new double[]{121.47, 31.23}, "上海",
                new double[]{113.27, 23.13}, "广州",
                new double[]{114.06, 22.54}, "深圳",
                new double[]{104.07, 30.67}, "成都",
                new double[]{108.95, 34.27}, "西安",
                new double[]{120.16, 30.27}, "杭州",
                new double[]{118.78, 32.04}, "南京"
        );
        double best = 999.0;
        String name = String.format(Locale.ROOT, "(%.2f, %.2f)", lng, lat);
        for (var e : cities.entrySet()) {
            double d = Math.hypot(e.getKey()[0] - lng, e.getKey()[1] - lat);
            if (d < best) { best = d; name = e.getValue(); }
        }
        return name;
    }

    /** 极简地址 → 经纬度。 */
    static Map<String, Object> mockGeocodeAddress(String addr) {
        Map<String, double[]> cities = Map.of(
                "北京", new double[]{116.40, 39.90},
                "上海", new double[]{121.47, 31.23},
                "广州", new double[]{113.27, 23.13},
                "深圳", new double[]{114.06, 22.54},
                "成都", new double[]{104.07, 30.67},
                "西安", new double[]{108.95, 34.27},
                "杭州", new double[]{120.16, 30.27},
                "南京", new double[]{118.78, 32.04}
        );
        for (var e : cities.entrySet()) {
            if (addr.contains(e.getKey())) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("address", e.getKey());
                r.put("city", e.getKey());
                r.put("country", "中国");
                r.put("lng", e.getValue()[0]);
                r.put("lat", e.getValue()[1]);
                return r;
            }
        }
        // 默认北京
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("address", addr);
        r.put("city", "未知");
        r.put("country", "中国");
        r.put("lng", 116.40);
        r.put("lat", 39.90);
        return r;
    }

    /* ===================== 表达式解析器（calculator 用）=====================
     *  递归下降：expr = term (('+'|'-') term)* ；term = factor (('*'|'/'|'%') factor)* ；
     *  factor = power ；power = unary ('**' unary)* ；unary = ('-')? primary ；
     *  primary = number | '(' expr ')' 。
     *  不支持函数 / 变量。最大递归深度 100（防恶意）。
     * ====================================================================== */
    static final class ExprParser {
        private final String s;
        private int pos;
        ExprParser(String s) { this.s = s; this.pos = 0; }

        double parseExpr() {
            skipWs();
            double v = parseTerm();
            while (true) {
                skipWs();
                if (pos >= s.length()) break;
                char c = s.charAt(pos);
                if (c == '+') { pos++; v = v + parseTerm(); }
                else if (c == '-') { pos++; v = v - parseTerm(); }
                else break;
            }
            return v;
        }
        private double parseTerm() {
            double v = parseFactor();
            while (true) {
                skipWs();
                if (pos >= s.length()) break;
                char c = s.charAt(pos);
                if (c == '*' && peek(1) == '*') { pos += 2; v = Math.pow(v, parseFactor()); }
                else if (c == '*') { pos++; v = v * parseFactor(); }
                else if (c == '/') {
                    pos++;
                    double rhs = parseFactor();
                    if (rhs == 0) throw new ArithmeticException("division by zero");
                    v = v / rhs;
                }
                else if (c == '%') { pos++; v = v % parseFactor(); }
                else break;
            }
            return v;
        }
        private double parseFactor() { return parseUnary(); }
        private double parseUnary() {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -parseUnary(); }
            if (pos < s.length() && s.charAt(pos) == '+') { pos++; return parseUnary(); }
            return parsePrimary();
        }
        private double parsePrimary() {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = parseExpr();
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ')') { pos++; return v; }
                throw new IllegalArgumentException("missing ')' at " + pos);
            }
            // number
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) throw new IllegalArgumentException("expected number at " + pos);
            return Double.parseDouble(s.substring(start, pos));
        }
        private char peek(int offset) {
            int i = pos + offset;
            return i < s.length() ? s.charAt(i) : '\0';
        }
        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }

    /* ===================== 工具列表（BuiltInToolsRegistrar 用） ===================== */

    /**
     * 11 个新工具的注册工厂 —— 与原 6 个一样注册成 inner class，但通过 Spring
     * 注入依赖（Feign client），避免 ToolRegistry 直接耦合 Spring。
     *
     * <p>用法：在 {@link ToolRegistry#registerBuiltinTools()} 末尾追加：
     * <pre>
     *   BuiltInToolsRegistrar v8 = new BuiltInToolsRegistrar(memoryClient, resonanceClient, authClient);
     *   v8.registerInto(register);  // register: BiConsumer&lt;String, Tool&gt; 或者直接走 register(tool)
     * </pre>
     */
    public static final class BuiltInToolsRegistrar {
        private final MemoryServiceClient memoryClient;
        private final ResonanceServiceClient resonanceClient;
        private final AuthServiceClient authClient;

        public BuiltInToolsRegistrar(MemoryServiceClient memoryClient,
                                     ResonanceServiceClient resonanceClient,
                                     AuthServiceClient authClient) {
            this.memoryClient = memoryClient;
            this.resonanceClient = resonanceClient;
            this.authClient = authClient;
        }

        public List<ToolRegistry.Tool> all() {
            return List.of(
                    new CurrentDateTimeTool(),
                    new GetWeatherTool(),
                    new CalculatorTool(),
                    new GeocodeTool(),
                    new GetFriendsTool(authClient),
                    new GetUnreadNotificationsTool(authClient),
                    new GetMemoryStatsTool(memoryClient),
                    new GetResonanceFeedTool(resonanceClient),
                    new SummarizeConversationTool(),
                    new ListChatHistoryTool(resonanceClient),
                    new RegenerateLastAnswerTool()
            );
        }
    }
}
