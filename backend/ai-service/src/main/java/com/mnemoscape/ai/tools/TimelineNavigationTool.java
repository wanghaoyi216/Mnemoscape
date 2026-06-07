package com.mnemoscape.ai.tools;

import com.mnemoscape.ai.client.MemoryServiceClient;
import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.function.Function;

/**
 * 时间线导航工具 — 让 AI 按时间范围检索用户记忆。
 *
 * <p>典型触发："帮我回忆 2022 年夏天的事"、"最近一个月我记录了什么"。
 * 模型调用此工具获取指定时间段内的记忆摘要列表。
 */
@Configuration
public class TimelineNavigationTool {

    private static final Logger log = LoggerFactory.getLogger(TimelineNavigationTool.class);

    public static class Request {
        public Integer yearFrom;
        public Integer yearTo;
        public String season;
        public String location;
        public Integer limit;
    }

    public static class MemorySummary {
        public String id;
        public String title;
        public String location;
        public Integer year;
        public String season;
        public String createdAt;
    }

    public static class Response {
        public List<MemorySummary> memories = new ArrayList<>();
        public int totalFound;
        public boolean degraded = false;
        public String message;
    }

    private final MemoryServiceClient memoryClient;

    public TimelineNavigationTool(MemoryServiceClient memoryClient) {
        this.memoryClient = memoryClient;
    }

    @Bean
    public FunctionCallback timelineNavigationToolCallback() {
        Function<Request, Response> fn = this::navigate;
        return FunctionCallback.builder()
                .description("Navigate the user's memory timeline by year range, season, or location. "
                        + "Returns a list of memory summaries (id, title, location, year, season). "
                        + "Use when the user asks about memories from a specific time period, "
                        + "season, or place — e.g. '2022年夏天', '最近的记忆', '在北京的回忆'.")
                .function("timelineNavigationTool", fn)
                .inputType(Request.class)
                .build();
    }

    @SuppressWarnings("unchecked")
    public Response navigate(Request req) {
        Response resp = new Response();
        String userId = MilvusSearchTool.currentUserId();
        if (userId == null) {
            resp.degraded = true;
            resp.message = "Unauthenticated";
            return resp;
        }

        try {
            ApiResponse<Map<String, Object>> raw = memoryClient.listMemories(0, 50, userId);
            if (raw == null || raw.getData() == null) {
                resp.message = "No memories found";
                return resp;
            }

            List<Map<String, Object>> content = (List<Map<String, Object>>) raw.getData().get("content");
            if (content == null) {
                resp.message = "No memories found";
                return resp;
            }

            int limit = (req.limit != null && req.limit > 0) ? Math.min(req.limit, 20) : 10;

            for (Map<String, Object> m : content) {
                Integer year = m.get("memoryYear") instanceof Number n ? n.intValue() : null;
                String season = m.get("memorySeason") != null ? String.valueOf(m.get("memorySeason")) : null;
                String location = m.get("memoryLocation") != null ? String.valueOf(m.get("memoryLocation")) : null;

                if (req.yearFrom != null && (year == null || year < req.yearFrom)) continue;
                if (req.yearTo != null && (year == null || year > req.yearTo)) continue;
                if (req.season != null && !req.season.isBlank() && !req.season.equalsIgnoreCase(season)) continue;
                if (req.location != null && !req.location.isBlank()
                        && (location == null || !location.contains(req.location))) continue;

                MemorySummary s = new MemorySummary();
                s.id = String.valueOf(m.get("id"));
                s.title = String.valueOf(m.get("title"));
                s.location = location;
                s.year = year;
                s.season = season;
                s.createdAt = String.valueOf(m.get("createdAt"));
                resp.memories.add(s);

                if (resp.memories.size() >= limit) break;
            }

            resp.totalFound = resp.memories.size();
            return resp;
        } catch (Exception e) {
            log.warn("timelineNavigationTool degraded: {}", e.toString());
            resp.degraded = true;
            resp.message = "memory-service error: " + e.getClass().getSimpleName();
            return resp;
        }
    }
}
