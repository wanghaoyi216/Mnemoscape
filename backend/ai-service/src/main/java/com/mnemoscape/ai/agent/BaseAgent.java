package com.mnemoscape.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.exception.AiUpstreamException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 抽象智能体基类 (BaseAgent)。
 * 提供通用的模型执行机制，支持同步 execute 与响应式流式 executeStream 调用。
 */
public abstract class BaseAgent {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final HttpClient httpClient; // 主要是为了兼容英伟达的视觉模型
    protected final ObjectMapper json = new ObjectMapper(); // 序列化工具

    protected final String baseUrl;
    protected final String apiKey;

    protected BaseAgent(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    protected BaseAgent(String baseUrl, String apiKey, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    /** 获取智能体绑定的具体模型名称 */
    public abstract String getModelName();

    /** 获取智能体独特的系统提示词 */
    public abstract String getSystemPrompt();

    /** 执行智能体流式推理 (Flux<String>) */
    public Flux<String> executeStream(String userPrompt) {
        return executeStream(userPrompt, 0.7, 4096);
    }

    /**
     * 响应式流式推理执行方法。
     * 向模型接口发起 SSE 流式请求并逐 token 产出 Flux<String>。
     */
    public Flux<String> executeStream(String userPrompt, double temperature, int maxTokens) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("nvapi-placeholder")) {
            return Flux.error(new AiUpstreamException(AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY is not configured for Agent: " + getClass().getSimpleName()));
        }
        return Flux.<String>create(sink -> {
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
                payload.put("stream", true);

                // CoT/思索链模型配置特有的 thinking_budget
                if (getClass().getSimpleName().contains("Chain") || getModelName().contains("seed") || getModelName().contains("reasoning")) {
                    ObjectNode extraBody = payload.putObject("extra_body");
                    extraBody.put("thinking_budget", -1);
                }

                String requestBody = json.writeValueAsString(payload);

                HttpRequest httpReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "text/event-stream, application/json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

                httpClient.sendAsync(httpReq, HttpResponse.BodyHandlers.ofLines())
                        .whenComplete((resp, error) -> {
                            if (error != null) {
                                sink.error(new AiUpstreamException(AiUpstreamException.Reason.UNKNOWN,
                                        "Agent streaming connection failed: " + error.getMessage(), error));
                                return;
                            }
                            int status = resp.statusCode();
                            if (status >= 400) {
                                sink.error(new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                                        "NVIDIA API returned HTTP " + status));
                                return;
                            }
                            try (var stream = resp.body()) {
                                stream.forEach(line -> {
                                    if (line == null) return;
                                    String trimmed = line.trim();
                                    if (trimmed.isEmpty() || trimmed.startsWith(":")) return;
                                    if (trimmed.startsWith("data:")) {
                                        String data = trimmed.substring(5).trim();
                                        if ("[DONE]".equals(data)) {
                                            return;
                                        }
                                        try {
                                            JsonNode root = json.readTree(data);
                                            JsonNode choices = root.path("choices");
                                            if (choices.isArray() && choices.size() > 0) {
                                                JsonNode delta = choices.get(0).path("delta");
                                                String content = delta.path("content").asText(null);
                                                if (content != null && !content.isEmpty()) {
                                                    sink.next(content);
                                                }
                                            }
                                        } catch (Exception e) {
                                            log.debug("[{}] SSE chunk parse error for data: {}", getClass().getSimpleName(), data, e);
                                        }
                                    }
                                });
                                sink.complete();
                            } catch (Exception e) {
                                sink.error(e);
                            }
                        });
            } catch (Exception e) {
                sink.error(new AiUpstreamException(AiUpstreamException.Reason.UNKNOWN,
                        "Agent streaming error: " + e.getMessage(), e));
            }
        });
    }

    /** 执行智能体推理（熔断保护：上游持续故障时走 fallback 返回空串，避免拖垮调用方） */
    @CircuitBreaker(name = "deepseek", fallbackMethod = "executeFallback")
    public String execute(String userPrompt) {
        return execute(userPrompt, 0.7, 4096);
    }

    private String executeFallback(String userPrompt, Throwable t) {
        log.warn("[circuit-breaker] Agent {} execute fallback: {}", getClass().getSimpleName(), t.toString());
        return "";
    }

    public String execute(String userPrompt, double temperature, int maxTokens) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("nvapi-placeholder")) {
            throw new AiUpstreamException(AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY is not configured for Agent: " + getClass().getSimpleName());
        }
        try {
            ObjectNode payload = json.createObjectNode(); // Jackson对JSON的内存表示，用于手动拼接JSON
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
            
            String requestBody = json.writeValueAsString(payload); // 序列化为字符串

            // 发送给英伟达模型的请求信息
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            // 获取模型响应
            HttpResponse<String> resp = httpClient.send(httpReq, 
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            
            int status = resp.statusCode();
            if (status >= 400) {
                log.warn("[{}] generate API error: HTTP {} {}", getClass().getSimpleName(), status, resp.body());
                throw new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                        "NVIDIA API returned HTTP " + status + ": " + resp.body());
            }

            // 反序列化解析模型返回内容
            JsonNode rootNode = json.readTree(resp.body());
            JsonNode choices = rootNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText("");
                return content.trim();
            }
            throw new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_ERROR,
                    "Empty response body from NVIDIA API");
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] execution failed: {}", getClass().getSimpleName(), e.getMessage());
            throw new AiUpstreamException(AiUpstreamException.Reason.UNKNOWN,
                    "Agent execution error: " + e.getMessage(), e);
        }
    }
}
