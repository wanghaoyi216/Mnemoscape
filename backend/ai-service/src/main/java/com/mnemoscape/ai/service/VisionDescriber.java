package com.mnemoscape.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 多模态视觉描述器 — Mnemoscape 的"视觉前置"层。
 *
 * <p><b>背景</b>：默认基座 MiniMax M2.7 仅文本，{@link ChatReasoner} 检测到附件
 * 时先调用本服务跑一次"图片→中文密集描述"，再把描述 prepend 到给基座的 prompt。
 * 这样保留 M2.7 的中文写作 / agentic / 工具调用强项，同时获得真正的视觉理解。
 *
 * <p><b>选型</b>：默认 {@code qwen/qwen3.5-397b-a17b}（早融合 VL，免费试用，OpenAI 兼容协议）。
 * 主选不可用时降级到 {@code moonshotai/kimi-k2.5}（1T 多模态 MoE，同样在 NVIDIA Integrate）。
 * 两者都不可用时由调用方退化为"用文件名 + URL 注入 prompt"，让基座至少知道有附件。
 *
 * <p><b>OpenAI 兼容协议</b>：messages.content 为 array，每条 part 为
 * <ul>
 *   <li>{@code {"type":"text","text":"..."}}</li>
 *   <li>{@code {"type":"image_url","image_url":{"url":"https://..."}}}</li>
 * </ul>
 *
 * <p><b>失败模式</b>：
 * <ul>
 *   <li>未配置 NVIDIA_API_KEY → 直接抛 {@link AiUpstreamException}（同基座一致）</li>
 *   <li>主选模型 4xx/5xx → 自动降级到 fallback model</li>
 *   <li>双模型都失败 → 抛 AiUpstreamException，调用方降级到"文件名注入"</li>
 * </ul>
 */
@Service
public class VisionDescriber {

    private static final Logger log = LoggerFactory.getLogger(VisionDescriber.class);

    /** OpenAI 兼容路径，与 Spring AI 约定一致（base-url 不带 /v1）。 */
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** 单张图片下载上限。视觉模型一般 5MB 一张就够，再大没必要白白塞 base64。 */
    private static final long MAX_IMAGE_BYTES = 6L * 1024 * 1024;

    private final AiUpstreamProperties props;
    private final String configuredApiKey;
    private final String baseUrl;
    /** JDK 标准 HttpClient — 比 Spring RestClient 更鲁棒地处理 application/octet-stream 等
     *  非典型 Content-Type；无需 message converter 配合。 */
    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();
    /** 限流层 (C-3)；缺 bean / Redis 不可用时静默放行。 */
    private final org.springframework.beans.factory.ObjectProvider<AiCacheService> aiCacheProvider;

    public VisionDescriber(AiUpstreamProperties props,
                           org.springframework.core.env.Environment env,
                           org.springframework.beans.factory.ObjectProvider<AiCacheService> aiCacheProvider,
                           @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}")
                           String baseUrl) {
        this.props = props;
        this.configuredApiKey = env.getProperty("spring.ai.openai.api-key", "");
        this.aiCacheProvider = aiCacheProvider;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 把若干图片 URL 喂给视觉模型，返回中文（或英文）密集描述。
     *
     * @param imageUrls 图片 URL 列表（http/https，建议来自 asset-service presigned）
     * @param zh        true → 要求中文描述；false → 英文
     * @return 视觉模型给出的纯文本描述（不含 markdown / 列表）
     * @throws AiUpstreamException 主备模型均失败 / 缺 key / 超时
     */
    public String describe(List<String> imageUrls, boolean zh) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return "";
        }
        ensureRealKeyOrThrow();
        // C-3: 视觉模型限流。Vision 调用是最贵的 (~5-50s + 大 Token 消耗)，
        // 默认 RPM 桶最小 (20)；超额抛 RATE_LIMITED 比让 NVIDIA 返 429 更友好。
        AiCacheService cache = aiCacheProvider.getIfAvailable();
        if (cache != null) {
            cache.acquireOrThrow(AiCacheService.BUCKET_VISION);
        }

        // 限流：超出上限只取前 N 张，避免 prompt 失控 + 费用爆炸
        List<String> capped = imageUrls.stream()
                .filter(u -> u != null && !u.isBlank())
                .limit(props.getVisionMaxImages())
                .toList();
        if (capped.isEmpty()) return "";

        // ⚠️ 关键：MinIO presigned URL 大概率指向 Tailscale 私有 IP（如 100.x.x.x），
        //   NVIDIA Integrate 公网服务器无法直接访问。先在本服务里下载图片，
        //   编成 base64 data URI 再塞进 OpenAI Vision messages —— 这样视觉模型
        //   不再需要"自己去抓 URL"，跨网段问题彻底消失。
        List<String> dataUriOrUrl = inlineAsDataUri(capped);

        String primary = props.getVisionModel();
        String fallback = props.getVisionFallbackModel();

        try {
            return callOnce(primary, dataUriOrUrl, zh);
        } catch (Exception primaryErr) {
            log.warn("[VisionDescriber] primary model={} failed: {}; trying fallback={}",
                    primary, primaryErr.getMessage(), fallback);
            if (fallback == null || fallback.isBlank() || fallback.equals(primary)) {
                throw classify(primaryErr);
            }
            try {
                return callOnce(fallback, dataUriOrUrl, zh);
            } catch (Exception fallbackErr) {
                log.warn("[VisionDescriber] fallback model={} also failed: {}",
                        fallback, fallbackErr.getMessage());
                throw classify(fallbackErr);
            }
        }
    }

    /**
     * 把每个 URL 下载成字节流，编成 OpenAI Vision 接受的 {@code data:image/<mime>;base64,<...>} URI。
     * 单张失败不阻塞其他张：失败的那张会回退为原 URL（让视觉模型至少看到尝试，且日志可追）。
     */
    private List<String> inlineAsDataUri(List<String> urls) {
        List<String> out = new ArrayList<>(urls.size());
        for (String url : urls) {
            try {
                HttpRequest fetch = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = httpClient.send(fetch, HttpResponse.BodyHandlers.ofByteArray());
                int status = resp.statusCode();
                byte[] bytes = resp.body();
                if (status >= 400) {
                    log.warn("[VisionDescriber] image fetch HTTP {} for {}", status, abbrev(url));
                    out.add(url);
                    continue;
                }
                if (bytes == null || bytes.length == 0) {
                    log.warn("[VisionDescriber] image download empty for {}", abbrev(url));
                    out.add(url);
                    continue;
                }
                if (bytes.length > MAX_IMAGE_BYTES) {
                    log.warn("[VisionDescriber] image too large ({}MB) for {} — skipping inline",
                            bytes.length / 1024 / 1024, abbrev(url));
                    out.add(url);
                    continue;
                }
                String mime = guessMime(url, bytes);
                String b64 = Base64.getEncoder().encodeToString(bytes);
                out.add("data:" + mime + ";base64," + b64);
            } catch (Exception e) {
                log.warn("[VisionDescriber] failed to download {} ({}); falling back to raw URL",
                        abbrev(url), e.getMessage());
                out.add(url);
            }
        }
        return out;
    }

    /** 优先看魔数，再看 URL 后缀，最后兜底 image/jpeg。 */
    private String guessMime(String url, byte[] bytes) {
        if (bytes.length >= 4) {
            // PNG 89 50 4E 47
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
            // JPEG FF D8 FF
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return "image/jpeg";
            // GIF 47 49 46 38
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return "image/gif";
            // WebP: RIFF....WEBP
            if (bytes.length >= 12
                    && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        // 去 query 后再判断后缀
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String abbrev(String s) {
        if (s == null) return "null";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    /* ------------------------------------------------------------------ */

    private String callOnce(String model, List<String> imageUrls, boolean zh) throws Exception {
        ObjectNode body = buildRequestBody(model, imageUrls, zh);
        String requestBody = json.writeValueAsString(body);

        long t0 = System.currentTimeMillis();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "") + CHAT_COMPLETIONS_PATH))
                .timeout(Duration.ofMillis(Math.max(props.getVisionTimeoutMs(), 30_000)))
                .header("Authorization", "Bearer " + configuredApiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        // ⚠️ 用 byte[] 接收：NVIDIA Integrate 在某些情况下会用 application/octet-stream
        //   返回 JSON 体（例如 422 / 模型异常），Spring RestClient 的 message converter
        //   会拒绝转 String / byte[]。改用 JDK HttpClient + 原始字节流绕开这个坑。
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        byte[] respBytes = resp.body();
        String responseJson = respBytes == null
                ? ""
                : new String(respBytes, StandardCharsets.UTF_8);

        if (status >= 400) {
            String snippet = responseJson.length() > 320
                    ? responseJson.substring(0, 320) + "..."
                    : responseJson;
            throw new RuntimeException("Vision upstream error: HTTP " + status + " " + snippet);
        }

        String content = extractContent(responseJson);
        log.info("[VisionDescriber] model={} images={} elapsedMs={} status={} contentLen={} respLen={}",
                model, imageUrls.size(), System.currentTimeMillis() - t0,
                status,
                content == null ? 0 : content.length(),
                responseJson.length());
        return content;
    }

    private ObjectNode buildRequestBody(String model, List<String> imageUrls, boolean zh) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        // 视觉模型不做流式（一次性短描述就够），temperature 取低值减少幻觉
        root.put("stream", false);
        root.put("temperature", 0.3);
        root.put("max_tokens", 512);

        ArrayNode messages = root.putArray("messages");

        // System prompt：始终英文 —— 经验上 OpenAI-style vision 模型对英文 system
        // prompt 服从度更高；Llama 3.2 Vision 在 image+text 场景也只输出英文。
        // 输出英文不影响最终面向用户的中文体验：基座 M2.7 会把这段英文描述吸收
        // 后用中文回答用户。zh 仅控制后面 user 文本的语气。
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content",
                "You are an expert image analyst. For each image the user provides, "
              + "produce a precise, dense English description. Cover: subject, setting, "
              + "people's actions/expressions if any, color/lighting/composition, "
              + "time/place clues, and prominent text. No markdown or bullet lists. "
              + "Mark each image as [Image 1] / [Image 2] / ...");

        // User: text + image_urls
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode parts = user.putArray("content");

        ObjectNode textPart = parts.addObject();
        textPart.put("type", "text");
        textPart.put("text", zh
                ? "请逐张描述以下图片。"
                : "Describe each of the following images.");

        for (String url : imageUrls) {
            ObjectNode imgPart = parts.addObject();
            imgPart.put("type", "image_url");
            ObjectNode imgUrl = imgPart.putObject("image_url");
            imgUrl.put("url", url);
        }
        return root;
    }

    /** 解析 OpenAI 兼容协议的 response body，取 {@code choices[0].message.content}。 */
    private String extractContent(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return "";
        try {
            JsonNode node = json.readTree(responseJson);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                JsonNode content = message.path("content");
                // 大多模态返回 content 是 string；防御性处理 array 情况
                if (content.isTextual()) {
                    return content.asText().trim();
                }
                if (content.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode part : content) {
                        if (part.path("type").asText("").equals("text")) {
                            sb.append(part.path("text").asText("")).append('\n');
                        }
                    }
                    return sb.toString().trim();
                }
            }
            log.warn("[VisionDescriber] unexpected response shape: {}",
                    responseJson.length() > 320 ? responseJson.substring(0, 320) + "..." : responseJson);
            return "";
        } catch (Exception e) {
            log.warn("[VisionDescriber] failed to parse response: {}", e.getMessage());
            return "";
        }
    }

    private void ensureRealKeyOrThrow() {
        if (configuredApiKey == null
                || configuredApiKey.isBlank()
                || configuredApiKey.startsWith(props.getPlaceholderKeyPrefix())) {
            throw new AiUpstreamException(
                    AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY is not configured; vision pre-pass cannot run.");
        }
    }

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
        return new AiUpstreamException(reason,
                "视觉模型调用失败 (" + (msg == null ? "" : msg) + ")", t);
    }
}
