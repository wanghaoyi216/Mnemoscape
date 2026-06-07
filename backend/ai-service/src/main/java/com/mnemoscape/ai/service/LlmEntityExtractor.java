package com.mnemoscape.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.ai.model.dto.EntityExtractRequest;
import com.mnemoscape.ai.model.dto.EntityExtractResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 版实体提取器（v1）— 通过基座模型做轻量 NER。
 *
 * <p>与 {@link EntityExtractor} 共享同一份契约（{@link EntityExtractResponse}），
 * 但提取来源是大模型而非关键词字典：
 * <ul>
 *   <li>覆盖率更高（捕捉字典外的人物/物件/情绪）</li>
 *   <li>对中文修辞 / 俗称 / 隐喻更稳健</li>
 * </ul>
 *
 * <p>失败语义：模型不可用 / JSON 不合规 → 抛 {@link RuntimeException}，
 * 由 {@code EntityExtractController} 透明降级到 {@link EntityExtractor}。
 */
@Service
public class LlmEntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmEntityExtractor.class);

    private final ChatClient chatClient;
    private final AiUpstreamProperties props;
    private final String configuredApiKey;
    private final ObjectMapper json = new ObjectMapper();

    public LlmEntityExtractor(@Qualifier("mnemoscapeChatClientBuilder") ChatClient.Builder builder,
                              AiUpstreamProperties props,
                              @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.chatClient = builder.build();
        this.props = props;
        this.configuredApiKey = apiKey;
    }

    public EntityExtractResponse extract(EntityExtractRequest request) {
        ensureRealKeyOrThrow();
        String desc = request.getDescription() == null ? "" : request.getDescription();
        if (desc.isBlank()) {
            return new EntityExtractResponse();
        }

        long t0 = System.currentTimeMillis();
        String raw = chatClient.prompt()
                .user(buildPrompt(desc, request.getMemoryLocation(), request.getMemoryYear()))
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .withModel(props.getAgenticModel())
                        .build())
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - t0;

        String jsonStr = extractFirstJsonObject(raw);
        if (jsonStr == null) {
            throw new IllegalStateException("LLM did not return parseable JSON; head="
                    + abbrev(raw, 220));
        }

        try {
            // Jackson 直接映射到 DTO（DTO 字段都是 List<String>，缺则视为空）
            EntityExtractResponse parsed = json.readValue(jsonStr, EntityExtractResponse.class);
            // 注入用户结构化字段（用户填的 location 永远先入）
            if (request.getMemoryLocation() != null && !request.getMemoryLocation().isBlank()) {
                List<String> locs = new ArrayList<>(
                        parsed.getLocations() == null ? List.of() : parsed.getLocations());
                String userLoc = request.getMemoryLocation().trim();
                if (!locs.contains(userLoc)) locs.add(0, userLoc);
                parsed.setLocations(locs);
            }
            // 安全去重 + 长度上限
            parsed.setPeople(dedupeAndLimit(parsed.getPeople(), 12));
            parsed.setLocations(dedupeAndLimit(parsed.getLocations(), 12));
            parsed.setObjects(dedupeAndLimit(parsed.getObjects(), 16));
            parsed.setEmotionTags(dedupeAndLimit(parsed.getEmotionTags(), 10));

            log.info("[LlmEntityExtractor] elapsedMs={} people={} locations={} objects={} emotions={}",
                    elapsed,
                    parsed.getPeople() == null ? 0 : parsed.getPeople().size(),
                    parsed.getLocations() == null ? 0 : parsed.getLocations().size(),
                    parsed.getObjects() == null ? 0 : parsed.getObjects().size(),
                    parsed.getEmotionTags() == null ? 0 : parsed.getEmotionTags().size());
            return parsed;
        } catch (com.fasterxml.jackson.core.JacksonException pe) {
            throw new IllegalStateException("LLM entity JSON parse failed: " + pe.getMessage()
                    + "; raw=" + abbrev(jsonStr, 200), pe);
        }
    }

    /* ------------------------------------------------------------------ */

    private void ensureRealKeyOrThrow() {
        if (configuredApiKey == null
                || configuredApiKey.isBlank()
                || configuredApiKey.startsWith(props.getPlaceholderKeyPrefix())) {
            throw new AiUpstreamException(
                    AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY is not configured; LLM entity extraction cannot run.");
        }
    }

    private String buildPrompt(String description, String memoryLocation, Integer memoryYear) {
        StringBuilder ctx = new StringBuilder();
        if (memoryLocation != null && !memoryLocation.isBlank()) {
            ctx.append("结构化字段：地点=\"").append(memoryLocation).append("\" ");
        }
        if (memoryYear != null) {
            ctx.append("年份=").append(memoryYear).append(" ");
        }

        return """
            你是 Mnemoscape 的命名实体提取器。从一段中文记忆描述中抽取四类实体，
            仅输出严格 JSON 对象，前后不要有任何说明文字、不要使用 markdown。
            
            JSON schema（四个字段都必须存在，数组可以为空）：
            {
              "people":     [string, ...]   // 人物：亲属、朋友、师长、爱人、孩子等
              "locations":  [string, ...]   // 地点：城市、街道、家中、学校、自然场所
              "objects":    [string, ...]   // 物件 / 自然元素：咖啡、雨、灯、信、海、月亮…
              "emotionTags":[string, ...]   // 情绪标签：开心、思念、平静、焦虑…
            }
            
            提取规则：
            - 用记忆中实际出现的语言；中文记忆抽中文，英文记忆抽英文
            - 同义词不去重（保持原文），但严格相同字面只出现一次
            - 不要凭空捏造没出现的人/物
            - 模糊指称（"她"/"那个朋友"）不输出
            - 每类最多 8 个；时间类（"那天"/"几年"）不要进 locations
            
            """ + (ctx.length() == 0 ? "" : ("额外上下文：" + ctx + "\n\n")) + """
            记忆描述：
            ---
            """ + safeDescription(description) + """
            ---
            
            现在输出符合 schema 的 JSON。
            """;
    }

    private static String extractFirstJsonObject(String text) {
        if (text == null) return null;
        Matcher fence = Pattern.compile("```(?:json)?\\s*\\n([\\s\\S]*?)\\n```").matcher(text);
        if (fence.find()) {
            String inner = fence.group(1).trim();
            if (inner.startsWith("{") && inner.endsWith("}")) return inner;
        }
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static List<String> dedupeAndLimit(List<String> in, int limit) {
        if (in == null) return new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            seen.add(t);
            if (seen.size() >= limit) break;
        }
        return new ArrayList<>(seen);
    }

    private static String safeDescription(String desc) {
        String d = desc == null ? "" : desc;
        if (d.length() > 1500) d = d.substring(0, 1500);
        return d.replace("```", "ˋˋˋ");
    }

    private static String abbrev(String s, int n) {
        if (s == null) return "null";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
