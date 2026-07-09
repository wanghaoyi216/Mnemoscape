package com.mnemoscape.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.client.MemoryServiceClient;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 日记生成服务 — 基于用户记忆生成文学性日记。
 *
 * <p>使用 NVIDIA generation model 分析用户的记忆集合，生成带有情感深度和
 * 文学性的日记条目。支持按时间段、主题、情感筛选记忆。
 */
@Service
public class DiaryGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DiaryGeneratorService.class);

    private final MemoryServiceClient memoryClient;
    private final AiUpstreamProperties props;
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();

    public DiaryGeneratorService(MemoryServiceClient memoryClient,
                                 AiUpstreamProperties props,
                                 Environment env,
                                 @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}") String baseUrl) {
        this.memoryClient = memoryClient;
        this.props = props;
        this.apiKey = env.getProperty("spring.ai.openai.api-key", "");
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static class DiaryRequest {
        public String userId;
        public Integer year;
        public String season;
        public String emotion;
        public Integer limit;
    }

    public static class DiaryEntry {
        public String title;
        public String content;
        public String date;
        public List<String> memoryIds;
        public String mood;
    }

    /**
     * 生成 AI 日记。
     */
    @SuppressWarnings("unchecked")
    public DiaryEntry generate(DiaryRequest req) {
        if (req == null || req.userId == null) {
            throw new IllegalArgumentException("userId required");
        }

        if (apiKey.startsWith(props.getPlaceholderKeyPrefix())) {
            throw new AiUpstreamException(
                    AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY not configured"
            );
        }

        try {
            // 1. 从 memory-service 拉取用户记忆
            ApiResponse<Map<String, Object>> raw = memoryClient.listMemories(0, 50, req.userId);
            if (raw == null || raw.getData() == null) {
                throw new IllegalStateException("No memories found");
            }

            Object items = raw.getData().get("items");
            if (!(items instanceof List<?> list)) {
                throw new IllegalStateException("Invalid memory data");
            }

            // 2. 筛选记忆
            List<Map<String, Object>> filtered = filterMemories(list, req);
            if (filtered.isEmpty()) {
                throw new IllegalStateException("No matching memories");
            }

            // 3. 构建 prompt
            String prompt = buildDiaryPrompt(filtered);

            // 4. 调用 LLM 生成日记
            String diaryText = callGenerationModel(prompt);

            // 5. 解析并返回
            DiaryEntry entry = new DiaryEntry();
            entry.title = extractTitle(diaryText);
            entry.content = diaryText;
            entry.date = java.time.LocalDate.now().toString();
            entry.memoryIds = filtered.stream()
                    .map(m -> String.valueOf(m.get("id")))
                    .toList();
            entry.mood = req.emotion != null ? req.emotion : "reflective";

            return entry;
        } catch (Exception e) {
            log.error("Diary generation failed: {}", e.getMessage(), e);
            throw new AiUpstreamException(
                    AiUpstreamException.Reason.UPSTREAM_ERROR,
                    "Diary generation failed: " + e.getMessage()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filterMemories(List<?> list, DiaryRequest req) {
        List<Map<String, Object>> result = new ArrayList<>();
        int limit = req.limit != null ? Math.min(req.limit, 20) : 10;

        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;

            if (req.year != null) {
                Integer year = m.get("memoryYear") instanceof Number n ? n.intValue() : null;
                if (year == null || !year.equals(req.year)) continue;
            }

            if (req.season != null && !req.season.isBlank()) {
                String season = String.valueOf(m.get("memorySeason"));
                if (!req.season.equalsIgnoreCase(season)) continue;
            }

            result.add((Map<String, Object>) m);
            if (result.size() >= limit) break;
        }

        return result;
    }

    private String buildDiaryPrompt(List<Map<String, Object>> memories) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位富有文学才华的作家。请基于以下记忆片段，创作一篇深刻、优美的日记。\n\n");
        sb.append("要求：\n");
        sb.append("1. 用第一人称叙述\n");
        sb.append("2. 融入情感和哲思\n");
        sb.append("3. 使用优美的文学语言\n");
        sb.append("4. 长度 300-500 字\n");
        sb.append("5. 开头给出一个诗意的标题（用 # 标记）\n\n");
        sb.append("记忆片段：\n\n");

        for (int i = 0; i < memories.size(); i++) {
            Map<String, Object> m = memories.get(i);
            sb.append(i + 1).append(". ");
            sb.append(m.get("title")).append("\n");
            String desc = String.valueOf(m.get("description"));
            if (desc.length() > 200) desc = desc.substring(0, 200) + "...";
            sb.append("   ").append(desc).append("\n");
            sb.append("   地点：").append(m.get("memoryLocation")).append("\n");
            sb.append("   时间：").append(m.get("memoryYear")).append(" ").append(m.get("memorySeason")).append("\n\n");
        }

        sb.append("请开始创作日记：\n");
        return sb.toString();
    }

    private String callGenerationModel(String prompt) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", props.getGenerationModel());
        body.put("temperature", 0.8);
        body.put("max_tokens", 1024);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                    "HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = json.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    private String extractTitle(String diaryText) {
        String[] lines = diaryText.split("\n");
        for (String line : lines) {
            if (line.startsWith("#")) {
                return line.replaceFirst("^#+\\s*", "").trim();
            }
        }
        return "无题";
    }
}
