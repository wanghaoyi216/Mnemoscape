package com.mnemoscape.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.ai.exception.AiUpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态推荐问题（intentHints）生成器。
 *
 * <p>取代前端 AiMascotDock 里硬编码的 4 条静态快捷短语：结合用户最近的记忆
 * （标题 / 地点 / 年份）让 LLM 生成 3-4 条有温度的个性化引导问题，例如
 * "你前年在大理留下的那段回忆，现在想聊聊吗？"。
 *
 * <p><b>降级</b>：无记忆 / LLM 不可用 / 解析失败时返回一组通用兜底短语，
 * 保证前端永远拿得到可点的 hint。
 */
@Service
public class IntentHintService {

    private static final Logger log = LoggerFactory.getLogger(IntentHintService.class);

    private static final List<String> FALLBACK_ZH = List.of(
            "帮我找找最近的记忆", "整理一下我的情绪轨迹", "谁与我共鸣最强", "基于回忆推荐一段冥想");
    private static final List<String> FALLBACK_EN = List.of(
            "Find my recent memories", "Summarize my mood lately",
            "Who resonates with me most?", "Suggest a meditation from my memories");

    private final ChatClient chatClient;
    private final ObjectMapper json = new ObjectMapper();

    public IntentHintService(@Qualifier("mnemoscapeChatClientBuilder") ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /** 一条记忆的精简上下文（与前端 digest 对齐）。 */
    public record MemoryDigest(String title, String location, Integer year) {}

    /**
     * Compute a deterministic MD5 hash of the memory digests + locale to serve as a compact cache key.
     */
    public static String cacheKey(List<MemoryDigest> digests, boolean zh) {
        if (digests == null || digests.isEmpty()) {
            return "fallback|" + zh;
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (MemoryDigest d : digests) {
                if (d == null) continue;
                sb.append(d.title() == null ? "" : d.title()).append('|');
                sb.append(d.location() == null ? "" : d.location()).append('|');
                sb.append(d.year() == null ? "" : d.year()).append('|');
            }
            byte[] hash = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString() + "|" + zh;
        } catch (Exception e) {
            return "error|" + zh + "|" + digests.size();
        }
    }

    /**
     * 生成 3-4 条个性化推荐问题。
     *
     * @param digests 用户最近记忆摘要（可空）
     * @param zh      true → 中文；false → 英文
     */
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = com.mnemoscape.ai.config.AiCacheConfig.CACHE_INTENT_HINTS,
            key = "T(com.mnemoscape.ai.service.IntentHintService).cacheKey(#digests, #zh)",
            sync = true
    )
    public List<String> generate(List<MemoryDigest> digests, boolean zh) {
        List<String> fallback = zh ? FALLBACK_ZH : FALLBACK_EN;
        if (digests == null || digests.isEmpty()) {
            return fallback;
        }
        try {
            String memoryLines = buildMemoryLines(digests);
            String prompt = (zh
                    ? "你是记忆博物馆的 AI 助手。下面是用户最近的一些记忆片段。请基于这些线索，"
                    + "生成 3 到 4 条用户可能想点开向你提问的『快捷问题』，每条不超过 16 个字，"
                    + "要具体、有温度、能勾起回忆。只输出一个 JSON 字符串数组，不要任何解释。\n\n"
                    + "【最近记忆】\n" + memoryLines
                    : "You are the AI assistant of a memory museum. Below are some of the user's recent "
                    + "memories. Generate 3 to 4 short suggested questions (each under 8 words) the user "
                    + "might want to click and ask you. Be specific and evocative. Output ONLY a JSON array "
                    + "of strings, no explanation.\n\n【Recent memories】\n" + memoryLines);

            String raw = chatClient.prompt().user(prompt).call().content();
            List<String> parsed = parseJsonArray(raw);
            if (parsed.isEmpty()) {
                log.warn("[IntentHint] LLM returned no parseable hints; using fallback");
                return fallback;
            }
            // 限制 4 条，去掉过长项
            List<String> out = new ArrayList<>();
            for (String s : parsed) {
                if (s == null) continue;
                String t = s.strip();
                if (t.isEmpty()) continue;
                if (t.length() > 40) t = t.substring(0, 40);
                out.add(t);
                if (out.size() >= 4) break;
            }
            return out.isEmpty() ? fallback : out;
        } catch (AiUpstreamException e) {
            log.warn("[IntentHint] upstream unavailable, using fallback: {}", e.getMessage());
            return fallback;
        } catch (Exception e) {
            log.warn("[IntentHint] generation failed, using fallback: {}", e.toString());
            return fallback;
        }
    }

    private String buildMemoryLines(List<MemoryDigest> digests) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(digests.size(), 8);
        for (int i = 0; i < n; i++) {
            MemoryDigest d = digests.get(i);
            sb.append("- ");
            if (d.title() != null && !d.title().isBlank()) sb.append(d.title());
            if (d.location() != null && !d.location().isBlank()) sb.append("（").append(d.location()).append("）");
            if (d.year() != null && d.year() > 0) sb.append(" ").append(d.year());
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 从 LLM 输出里抽取 JSON 字符串数组（容忍 markdown code fence / 前后噪声）。 */
    private List<String> parseJsonArray(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        String text = raw.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        String arr = text.substring(start, end + 1);
        try {
            JsonNode node = json.readTree(arr);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    if (item.isTextual()) out.add(item.asText());
                }
            }
        } catch (Exception e) {
            log.debug("[IntentHint] JSON parse failed for: {}", arr.length() > 120 ? arr.substring(0, 120) : arr);
        }
        return out;
    }
}
