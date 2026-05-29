package com.mnemoscape.ai.tools;

import com.mnemoscape.ai.client.MemoryServiceClient;
import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 当前用户记忆库的统计工具。
 *
 * <p>典型触发：用户问"我一共有多少条记忆？"、"我的公开记忆比例多少？"、
 * "最近一年我在哪些地方留下记忆？"。模型用本工具拿到聚合数字，
 * 而不是要求用户翻看列表。
 *
 * <p>实现：拉一页较大的 listMemories(0, 200)，在内存里聚合。这个数量级
 * 对个人记忆博物馆够用；超过 200 时返回 truncated=true 给模型作判断。
 */
@Configuration
public class MemoryStatsTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryStatsTool.class);

    public static class Request {
        /** 当前未使用，预留参数；模型可以传入主题关键词以限定统计范围。 */
        public String topic;
    }

    public static class Stats {
        public int total;
        public int privateCount;
        public int friendsCount;
        public int publicCount;
        public int lockedCount;
        public int withCoordsCount;
        public int withoutCoordsCount;
        public Map<String, Integer> yearHistogram = new LinkedHashMap<>();
        public Map<String, Integer> seasonHistogram = new LinkedHashMap<>();
        public Map<String, Integer> topLocations = new LinkedHashMap<>();
        public boolean truncated;
    }

    public static class Response {
        public Stats stats;
        public boolean degraded = false;
        public String message;
    }

    private final MemoryServiceClient memoryClient;

    public MemoryStatsTool(MemoryServiceClient memoryClient) {
        this.memoryClient = memoryClient;
    }

    @Bean
    public FunctionCallback memoryStatsToolCallback() {
        Function<Request, Response> fn = this::compute;
        return FunctionCallback.builder()
                .description("Compute statistics across the current user's memory library: "
                        + "total count, privacy distribution, year/season histogram, top "
                        + "locations, and how many memories are missing geocoding. Use when "
                        + "the user asks how many memories they have, where they recorded "
                        + "memories, or any aggregate question.")
                .function("memoryStatsTool", fn)
                .inputType(Request.class)
                .build();
    }

    @SuppressWarnings("unchecked")
    public Response compute(Request req) {
        Response resp = new Response();
        String userId = MilvusSearchTool.currentUserId();
        if (userId == null) {
            resp.degraded = true;
            resp.message = "Unauthenticated";
            return resp;
        }
        try {
            ApiResponse<Map<String, Object>> raw = memoryClient.listMemories(0, 200, userId);
            if (raw == null || raw.getData() == null) {
                resp.degraded = true;
                resp.message = "memory-service returned empty";
                return resp;
            }
            Object items = raw.getData().get("items");
            if (!(items instanceof List<?> list)) {
                resp.stats = new Stats();
                return resp;
            }
            Stats s = new Stats();
            long total = raw.getData().get("total") instanceof Number n ? n.longValue() : list.size();
            s.total = (int) total;
            if (s.total > list.size()) s.truncated = true;

            Map<String, Integer> yearCounter = new java.util.HashMap<>();
            Map<String, Integer> seasonCounter = new java.util.HashMap<>();
            Map<String, Integer> locCounter = new java.util.HashMap<>();
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> mWild)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) mWild;
                String privacy = String.valueOf(m.getOrDefault("privacyLevel", "PRIVATE")).toUpperCase();
                if ("PRIVATE".equals(privacy)) s.privateCount++;
                else if ("FRIENDS".equals(privacy)) s.friendsCount++;
                else if ("PUBLIC".equals(privacy)) s.publicCount++;
                if (Boolean.TRUE.equals(m.get("isLocked"))) s.lockedCount++;
                Object lng = m.get("memoryLng");
                Object lat = m.get("memoryLat");
                if (lng instanceof Number && lat instanceof Number) s.withCoordsCount++;
                else s.withoutCoordsCount++;

                Object year = m.get("memoryYear");
                if (year instanceof Number) {
                    String key = String.valueOf(((Number) year).intValue());
                    yearCounter.merge(key, 1, Integer::sum);
                }
                Object season = m.get("memorySeason");
                if (season != null && !String.valueOf(season).isBlank()) {
                    seasonCounter.merge(String.valueOf(season), 1, Integer::sum);
                }
                Object loc = m.get("memoryLocation");
                if (loc != null && !String.valueOf(loc).isBlank()) {
                    locCounter.merge(String.valueOf(loc), 1, Integer::sum);
                }
            }
            // 年份按升序
            yearCounter.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> s.yearHistogram.put(e.getKey(), e.getValue()));
            // 季节按计数降序
            seasonCounter.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> s.seasonHistogram.put(e.getKey(), e.getValue()));
            // top 8 location
            locCounter.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .forEach(e -> s.topLocations.put(e.getKey(), e.getValue()));

            resp.stats = s;
            return resp;
        } catch (Exception e) {
            log.warn("memoryStatsTool degraded: {}", e.toString());
            resp.degraded = true;
            resp.message = "memory-service error: " + e.getClass().getSimpleName();
            return resp;
        }
    }
}
