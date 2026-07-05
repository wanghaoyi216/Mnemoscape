package com.mnemoscape.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.exception.AiUpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 抽象智能体基类 (BaseAgent)。
 * 提供通用的模型执行机制，直接封装裸 HttpClient 调用以避开 Spring AI 兼容性限制。
 */
public abstract class BaseAgent {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final HttpClient httpClient;
    protected final ObjectMapper json = new ObjectMapper();

    protected final String baseUrl;
    protected final String apiKey;

    protected BaseAgent(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 获取智能体绑定的具体模型名称 */
    public abstract String getModelName();

    /** 获取智能体独特的系统提示词 */
    public abstract String getSystemPrompt();

    /** 执行智能体推理 */
    public String execute(String userPrompt) {
        return execute(userPrompt, 0.7, 4096);
    }

    public String execute(String userPrompt, double temperature, int maxTokens) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("nvapi-placeholder")) {
            throw new AiUpstreamException(AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY is not configured for Agent: " + getClass().getSimpleName());
        }
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("model", getModelName());
            
            ArrayNode messages = payload.putArray("messages");
            String sysPrompt = getSystemPrompt();
            if (sysPrompt != null && !sysPrompt.isBlank()) {
                messages.addObject().put("role", "system").put("content", sysPrompt);
            }
            messages.addObject().put("role", "user").put("content", userPrompt);
            
            payload.put("temperature", temperature);
            payload.put("max_tokens", maxTokens);
            payload.put("stream", false);

            // CoT/思索链模型配置特有的 thinking_budget
            if (getClass().getSimpleName().contains("Chain") || getModelName().contains("seed") || getModelName().contains("reasoning")) {
                ObjectNode extraBody = payload.putObject("extra_body");
                extraBody.put("thinking_budget", -1);
            }
            
            String requestBody = json.writeValueAsString(payload);
            
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            
            HttpResponse<String> resp = httpClient.send(httpReq, 
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            
            int status = resp.statusCode();
            if (status >= 400) {
                log.warn("[{}] generate API error: HTTP {} {}", getClass().getSimpleName(), status, resp.body());
                throw new RuntimeException("NVIDIA API returned HTTP " + status + ": " + resp.body());
            }
            
            JsonNode rootNode = json.readTree(resp.body());
            JsonNode choices = rootNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText("");
                return content.trim();
            }
            throw new RuntimeException("Empty response body from NVIDIA API");
        } catch (Exception e) {
            log.error("[{}] execution failed: {}", getClass().getSimpleName(), e.getMessage());
            throw new RuntimeException("Agent execution error: " + e.getMessage(), e);
        }
    }
}
