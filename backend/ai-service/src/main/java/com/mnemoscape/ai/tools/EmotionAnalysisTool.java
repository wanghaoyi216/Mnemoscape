package com.mnemoscape.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 情感分析工具 — 基于 LLM 的情感向量分析（v2 科学版）。
 *
 * <p>v1 使用关键词匹配，精度低。v2 改为调用 NVIDIA sentiment model 做 zero-shot
 * 情感分类，输出 8 维情感向量 + 主导情感 + 建议语气。
 *
 * <p>为避免循环依赖（ChatClient.Builder → tools → ChatClient.Builder），
 * 本工具直接使用 HttpClient 调用 NVIDIA API，不依赖 Spring AI ChatClient。
 */
@Configuration
public class EmotionAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(EmotionAnalysisTool.class);

    public static class Request {
        public String text;
    }

    public static class Response {
        public Map<String, Double> emotions;
        public String dominantEmotion;
        public double intensity;
        public String suggestedTone;
    }

    private final AiUpstreamProperties props;
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();

    public EmotionAnalysisTool(AiUpstreamProperties props,
                               Environment env,
                               @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}") String baseUrl) {
        this.props = props;
        this.apiKey = env.getProperty("spring.ai.openai.api-key", "");
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean
    public FunctionCallback emotionAnalysisToolCallback() {
        Function<Request, Response> fn = this::analyze;
        return FunctionCallback.builder()
                .description("Analyze the emotional tone of user text using AI sentiment model. "
                        + "Returns 8-dimensional emotion vector (joy, sadness, nostalgia, excitement, "
                        + "calm, melancholy, gratitude, anxiety) with scores 0-1, dominant emotion, "
                        + "and suggested response tone. Use when the user expresses strong feelings.")
                .function("emotionAnalysisTool", fn)
                .inputType(Request.class)
                .build();
    }

    public Response analyze(Request req) {
        Response resp = new Response();
        if (req == null || req.text == null || req.text.isBlank()) {
            resp.emotions = Map.of("calm", 0.5);
            resp.dominantEmotion = "calm";
            resp.intensity = 0.3;
            resp.suggestedTone = "gentle";
            return resp;
        }

        // 如果 API key 是占位符，直接降级到关键词分析
        if (apiKey.startsWith(props.getPlaceholderKeyPrefix())) {
            return fallbackKeywordAnalysis(req.text);
        }

        try {
            String prompt = buildPrompt(req.text);
            String raw = callModel(prompt);

            resp.emotions = parseEmotions(raw);
            resp.dominantEmotion = findDominant(resp.emotions);
            resp.intensity = resp.emotions.getOrDefault(resp.dominantEmotion, 0.5);
            resp.suggestedTone = mapTone(resp.dominantEmotion);
            return resp;
        } catch (Exception e) {
            log.warn("EmotionAnalysisTool model call failed, fallback to keyword: {}", e.getMessage());
            return fallbackKeywordAnalysis(req.text);
        }
    }

    private String callModel(String prompt) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", props.getSentimentModel());
        body.put("temperature", 0.3);
        body.put("max_tokens", 256);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = json.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    private String buildPrompt(String text) {
        return """
                Analyze the emotional tone of the following text and output ONLY a JSON object with 8 emotion scores (0.0-1.0):
                {
                  "joy": 0.0-1.0,
                  "sadness": 0.0-1.0,
                  "nostalgia": 0.0-1.0,
                  "excitement": 0.0-1.0,
                  "calm": 0.0-1.0,
                  "melancholy": 0.0-1.0,
                  "gratitude": 0.0-1.0,
                  "anxiety": 0.0-1.0
                }

                Text: """ + text + "\n\nJSON:";
    }

    private Map<String, Double> parseEmotions(String raw) {
        Map<String, Double> emotions = new LinkedHashMap<>();
        String[] keys = {"joy", "sadness", "nostalgia", "excitement", "calm", "melancholy", "gratitude", "anxiety"};

        for (String key : keys) {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.]+)");
            Matcher m = p.matcher(raw);
            if (m.find()) {
                emotions.put(key, Math.min(1.0, Math.max(0.0, Double.parseDouble(m.group(1)))));
            } else {
                emotions.put(key, 0.1);
            }
        }
        return emotions;
    }

    private String findDominant(Map<String, Double> emotions) {
        String dominant = "calm";
        double max = 0;
        for (var entry : emotions.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                dominant = entry.getKey();
            }
        }
        return dominant;
    }

    private String mapTone(String emotion) {
        return switch (emotion) {
            case "joy", "excitement" -> "warm_celebratory";
            case "sadness", "melancholy" -> "gentle_empathetic";
            case "nostalgia" -> "poetic_reflective";
            case "gratitude" -> "warm_affirming";
            case "anxiety" -> "calm_reassuring";
            default -> "gentle";
        };
    }

    private Response fallbackKeywordAnalysis(String text) {
        Response resp = new Response();
        String lower = text.toLowerCase();
        Map<String, Double> emotions = new LinkedHashMap<>();

        emotions.put("joy", scoreKeywords(lower, "开心", "快乐", "高兴", "幸福", "happy", "joy"));
        emotions.put("sadness", scoreKeywords(lower, "难过", "伤心", "悲伤", "sad", "lost"));
        emotions.put("nostalgia", scoreKeywords(lower, "怀念", "想念", "回忆", "remember", "used to"));
        emotions.put("excitement", scoreKeywords(lower, "激动", "兴奋", "期待", "excited", "amazing"));
        emotions.put("calm", scoreKeywords(lower, "平静", "安宁", "舒适", "peaceful", "calm"));
        emotions.put("melancholy", scoreKeywords(lower, "惆怅", "感伤", "bittersweet", "wistful"));
        emotions.put("gratitude", scoreKeywords(lower, "感谢", "感恩", "grateful", "thankful"));
        emotions.put("anxiety", scoreKeywords(lower, "焦虑", "担心", "害怕", "worried", "anxious"));

        resp.emotions = emotions;
        resp.dominantEmotion = findDominant(emotions);
        resp.intensity = emotions.getOrDefault(resp.dominantEmotion, 0.3);
        resp.suggestedTone = mapTone(resp.dominantEmotion);
        return resp;
    }

    private double scoreKeywords(String text, String... keywords) {
        int hits = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) hits++;
        }
        return Math.min(1.0, hits * 0.3);
    }
}
