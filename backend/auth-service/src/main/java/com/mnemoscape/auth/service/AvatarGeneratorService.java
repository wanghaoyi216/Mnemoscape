package com.mnemoscape.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户 3D 角色生成服务。
 *
 * <p>根据用户的自我描述文本，通过 NVIDIA Integrate API（OpenAI 兼容协议）
 * 生成结构化的 3D 角色特征数据，供 Three.js 渲染器直接消费。
 *
 * <p>双轨策略：
 * <ul>
 *   <li>LLM 可用时：调用 NVIDIA API 生成 grounded 的角色特征</li>
 *   <li>LLM 不可用时：基于关键词规则生成兜底角色特征</li>
 * </ul>
 */
@Service
public class AvatarGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(AvatarGeneratorService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com/v1}")
    private String baseUrl;

    @Value("${mnemoscape.ai.upstream.default-model:minimaxai/minimax-m2.7}")
    private String model;

    /**
     * 角色特征数据结构（供 Three.js 渲染器消费）。
     */
    public static class AvatarTraits {
        public String coreColor = "#6c63ff";
        public String auraColor = "#36d8b4";
        public String particleType = "stars";   // stars / fireflies / snowflakes / petals / embers / crystals
        public int particleCount = 1500;
        public String coreShape = "sphere";     // sphere / crystal / nebula / prism / torus
        public double auraIntensity = 0.75;
        public double rotationSpeed = 0.4;
        public double glowRadius = 2.5;
        public boolean trailEffect = true;
        public String secondaryColor = "#f59e0b";
        public double pulseFrequency = 1.2;     // 呼吸频率（Hz）
    }

    /**
     * 情绪基调数据结构（影响 3D 渲染的色温与粒子行为）。
     */
    public static class EmotionTone {
        public double warmth = 0.5;      // 暖色调倾向 0-1
        public double energy = 0.5;      // 活跃度 0-1
        public double depth = 0.5;       // 深沉感 0-1
        public double brightness = 0.5;  // 明亮度 0-1
        public double mystique = 0.5;    // 神秘感 0-1
    }

    /**
     * 完整的角色生成结果。
     */
    public static class AvatarGenerationResult {
        public AvatarTraits traits;
        public EmotionTone emotionTone;
        public List<String> personalityTags;
        public String avatarTitle;
        public String avatarStory;
    }

    /**
     * 根据用户自我描述生成 3D 角色特征。
     * LLM 失败时自动降级到规则版。
     */
    public AvatarGenerationResult generate(String selfDescription) {
        if (selfDescription == null || selfDescription.isBlank()) {
            return generateRuleBased("一个神秘的记忆收藏者");
        }

        // 检查 API key 是否配置
        if (isPlaceholderKey(apiKey)) {
            log.info("[AvatarGenerator] API key not configured, using rule-based generation");
            return generateRuleBased(selfDescription);
        }

        try {
            return generateWithLlm(selfDescription);
        } catch (Exception e) {
            log.warn("[AvatarGenerator] LLM generation failed, falling back to rule-based: {}", e.getMessage());
            return generateRuleBased(selfDescription);
        }
    }

    /**
     * 使用 LLM 生成角色特征（通过 NVIDIA Integrate API）。
     */
    private AvatarGenerationResult generateWithLlm(String selfDescription) throws Exception {
        String prompt = buildPrompt(selfDescription);

        // 使用 JDK 标准 HttpClient 调用 NVIDIA API（避免 Spring AI 依赖）
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                ),
                "max_tokens", 800,
                "temperature", 0.7
        ));

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM API returned status " + response.statusCode());
        }

        // 解析响应
        Map<?, ?> responseMap = objectMapper.readValue(response.body(), Map.class);
        List<?> choices = (List<?>) responseMap.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM returned empty choices");
        }
        Map<?, ?> firstChoice = (Map<?, ?>) choices.get(0);
        Map<?, ?> message = (Map<?, ?>) firstChoice.get("message");
        String content = (String) message.get("content");

        if (content == null || content.isBlank()) {
            throw new RuntimeException("LLM returned empty content");
        }

        // 提取 JSON
        String jsonStr = extractFirstJsonObject(content);
        if (jsonStr == null) {
            throw new RuntimeException("LLM did not return parseable JSON");
        }

        return parseLlmResult(jsonStr, selfDescription);
    }

    /**
     * 解析 LLM 返回的 JSON 结果。
     */
    @SuppressWarnings("unchecked")
    private AvatarGenerationResult parseLlmResult(String jsonStr, String selfDescription) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);

        AvatarGenerationResult result = new AvatarGenerationResult();
        result.traits = new AvatarTraits();
        result.emotionTone = new EmotionTone();

        // 解析 traits
        Map<String, Object> traitsMap = (Map<String, Object>) parsed.get("traits");
        if (traitsMap != null) {
            result.traits.coreColor = getString(traitsMap, "coreColor", "#6c63ff");
            result.traits.auraColor = getString(traitsMap, "auraColor", "#36d8b4");
            result.traits.particleType = validateParticleType(getString(traitsMap, "particleType", "stars"));
            result.traits.particleCount = getInt(traitsMap, "particleCount", 1500);
            result.traits.coreShape = validateCoreShape(getString(traitsMap, "coreShape", "sphere"));
            result.traits.auraIntensity = getDouble(traitsMap, "auraIntensity", 0.75);
            result.traits.rotationSpeed = getDouble(traitsMap, "rotationSpeed", 0.4);
            result.traits.glowRadius = getDouble(traitsMap, "glowRadius", 2.5);
            result.traits.trailEffect = getBool(traitsMap, "trailEffect", true);
            result.traits.secondaryColor = getString(traitsMap, "secondaryColor", "#f59e0b");
            result.traits.pulseFrequency = getDouble(traitsMap, "pulseFrequency", 1.2);
        }

        // 解析 emotionTone
        Map<String, Object> toneMap = (Map<String, Object>) parsed.get("emotionTone");
        if (toneMap != null) {
            result.emotionTone.warmth = getDouble(toneMap, "warmth", 0.5);
            result.emotionTone.energy = getDouble(toneMap, "energy", 0.5);
            result.emotionTone.depth = getDouble(toneMap, "depth", 0.5);
            result.emotionTone.brightness = getDouble(toneMap, "brightness", 0.5);
            result.emotionTone.mystique = getDouble(toneMap, "mystique", 0.5);
        }

        // 解析 personalityTags
        Object tagsObj = parsed.get("personalityTags");
        if (tagsObj instanceof List<?> tagsList) {
            result.personalityTags = new ArrayList<>();
            for (Object tag : tagsList) {
                if (tag instanceof String s && !s.isBlank()) {
                    result.personalityTags.add(s.trim());
                }
            }
        }
        if (result.personalityTags == null || result.personalityTags.isEmpty()) {
            result.personalityTags = generateRuleBasedTags(selfDescription);
        }

        result.avatarTitle = getString(parsed, "avatarTitle", null);
        result.avatarStory = getString(parsed, "avatarStory", null);

        // 兜底
        if (result.avatarTitle == null || result.avatarTitle.isBlank()) {
            result.avatarTitle = generateRuleBasedTitle(selfDescription);
        }
        if (result.avatarStory == null || result.avatarStory.isBlank()) {
            result.avatarStory = generateRuleBasedStory(selfDescription);
        }

        log.info("[AvatarGenerator] LLM generation succeeded: title={}", result.avatarTitle);
        return result;
    }

    /**
     * 基于关键词规则生成角色特征（LLM 不可用时的兜底）。
     */
    public AvatarGenerationResult generateRuleBased(String selfDescription) {
        String text = selfDescription == null ? "" : selfDescription.toLowerCase();

        AvatarGenerationResult result = new AvatarGenerationResult();
        result.traits = new AvatarTraits();
        result.emotionTone = new EmotionTone();

        // 根据关键词选择颜色主题
        if (containsAny(text, "夜", "星", "宇宙", "深邃", "神秘", "黑暗", "night", "star", "cosmos")) {
            result.traits.coreColor = "#4a6fa5";
            result.traits.auraColor = "#6c63ff";
            result.traits.particleType = "stars";
            result.traits.coreShape = "nebula";
            result.emotionTone.mystique = 0.85;
            result.emotionTone.depth = 0.8;
            result.emotionTone.brightness = 0.35;
        } else if (containsAny(text, "温暖", "阳光", "热情", "活泼", "开朗", "warm", "sunny", "bright")) {
            result.traits.coreColor = "#f59e0b";
            result.traits.auraColor = "#fbbf24";
            result.traits.particleType = "fireflies";
            result.traits.coreShape = "sphere";
            result.emotionTone.warmth = 0.9;
            result.emotionTone.energy = 0.8;
            result.emotionTone.brightness = 0.85;
        } else if (containsAny(text, "安静", "内敛", "沉稳", "平和", "冷静", "quiet", "calm", "peaceful")) {
            result.traits.coreColor = "#36d8b4";
            result.traits.auraColor = "#22d3ee";
            result.traits.particleType = "crystals";
            result.traits.coreShape = "crystal";
            result.emotionTone.depth = 0.7;
            result.emotionTone.mystique = 0.6;
            result.emotionTone.brightness = 0.55;
        } else if (containsAny(text, "浪漫", "诗意", "花", "春", "粉", "romantic", "poetic", "flower")) {
            result.traits.coreColor = "#f472b6";
            result.traits.auraColor = "#fb7185";
            result.traits.particleType = "petals";
            result.traits.coreShape = "sphere";
            result.emotionTone.warmth = 0.75;
            result.emotionTone.energy = 0.6;
            result.emotionTone.brightness = 0.7;
        } else if (containsAny(text, "冷", "雪", "冬", "孤独", "cold", "snow", "winter", "alone")) {
            result.traits.coreColor = "#93c5fd";
            result.traits.auraColor = "#bfdbfe";
            result.traits.particleType = "snowflakes";
            result.traits.coreShape = "prism";
            result.emotionTone.depth = 0.75;
            result.emotionTone.mystique = 0.65;
            result.emotionTone.warmth = 0.2;
        } else if (containsAny(text, "热血", "激情", "火", "勇敢", "passionate", "fire", "brave")) {
            result.traits.coreColor = "#ef4444";
            result.traits.auraColor = "#f97316";
            result.traits.particleType = "embers";
            result.traits.coreShape = "torus";
            result.emotionTone.energy = 0.9;
            result.emotionTone.warmth = 0.8;
            result.emotionTone.brightness = 0.75;
        } else {
            // 默认：紫色星空系
            result.traits.coreColor = "#6c63ff";
            result.traits.auraColor = "#36d8b4";
            result.traits.particleType = "stars";
            result.traits.coreShape = "sphere";
        }

        // 根据关键词调整粒子数量和强度
        if (containsAny(text, "丰富", "热闹", "活跃", "energetic", "vibrant")) {
            result.traits.particleCount = 2500;
            result.traits.auraIntensity = 0.9;
            result.traits.rotationSpeed = 0.7;
        } else if (containsAny(text, "安静", "简单", "极简", "quiet", "minimal")) {
            result.traits.particleCount = 800;
            result.traits.auraIntensity = 0.5;
            result.traits.rotationSpeed = 0.2;
        }

        result.personalityTags = generateRuleBasedTags(selfDescription);
        result.avatarTitle = generateRuleBasedTitle(selfDescription);
        result.avatarStory = generateRuleBasedStory(selfDescription);

        return result;
    }

    /**
     * 构建 LLM 提示词。
     */
    private String buildPrompt(String selfDescription) {
        return """
            你是 Mnemoscape 的用户 3D 角色刻画引擎。根据用户的自我描述，生成一份用于 Three.js 渲染的结构化 JSON。
            仅输出 JSON 对象，不要有任何说明文字。
            
            JSON 字段要求：
            - traits: 3D 角色特征对象，包含：
              - coreColor: 核心颜色（6位hex，如"#6c63ff"），必须与描述的气质/情绪匹配
              - auraColor: 光晕颜色（6位hex），与 coreColor 形成和谐对比
              - particleType: 粒子类型，仅可选 ["stars","fireflies","snowflakes","petals","embers","crystals"]
              - particleCount: 粒子数量（整数，300-3000），活跃/热情的人多，安静/内敛的人少
              - coreShape: 核心形状，仅可选 ["sphere","crystal","nebula","prism","torus"]
              - auraIntensity: 光晕强度（0.3-1.0）
              - rotationSpeed: 旋转速度（0.1-1.0）
              - glowRadius: 发光半径（1.5-4.0）
              - trailEffect: 是否有拖尾效果（true/false）
              - secondaryColor: 辅助颜色（6位hex）
              - pulseFrequency: 呼吸频率（0.5-2.5，单位Hz）
            - emotionTone: 情绪基调对象，包含：
              - warmth: 暖色调倾向（0-1）
              - energy: 活跃度（0-1）
              - depth: 深沉感（0-1）
              - brightness: 明亮度（0-1）
              - mystique: 神秘感（0-1）
            - personalityTags: 个性标签数组（3-5个中文短语，每个不超过6个字，紧扣描述）
            - avatarTitle: 角色诗意名号（一句话，不超过15个字，有意境）
            - avatarStory: 角色背景故事（一句话，不超过50个字，有诗意）
            
            禁止：
            - 不要输出 markdown / code fence
            - 不要套用通用模板，必须紧扣用户描述
            - personalityTags 必须是中文
            
            用户自我描述：
            ---
            """ + safeText(selfDescription) + """
            ---
            
            现在仅输出符合上述 schema 的 JSON 对象。
            """;
    }

    /**
     * 基于规则生成个性标签。
     */
    private List<String> generateRuleBasedTags(String text) {
        List<String> tags = new ArrayList<>();
        if (text == null) text = "";
        String lower = text.toLowerCase();

        if (containsAny(lower, "读书", "书", "阅读", "文学")) tags.add("书海漫游者");
        if (containsAny(lower, "音乐", "歌", "旋律", "乐器")) tags.add("音律感知者");
        if (containsAny(lower, "旅行", "旅游", "远方", "流浪")) tags.add("时空旅行者");
        if (containsAny(lower, "夜", "深夜", "凌晨", "失眠")) tags.add("夜行者");
        if (containsAny(lower, "星", "宇宙", "天文", "星空")) tags.add("星图收藏者");
        if (containsAny(lower, "安静", "内敛", "独处", "沉默")) tags.add("静默守望者");
        if (containsAny(lower, "创作", "写作", "画", "艺术")) tags.add("灵感捕捉者");
        if (containsAny(lower, "思考", "哲学", "思索", "沉思")) tags.add("深渊思考者");
        if (containsAny(lower, "自然", "山", "海", "森林")) tags.add("自然感应者");
        if (containsAny(lower, "记忆", "过去", "怀念", "回忆")) tags.add("记忆守护者");

        // 确保至少有3个标签
        if (tags.isEmpty()) tags.add("记忆收藏者");
        if (tags.size() < 2) tags.add("时光漫游者");
        if (tags.size() < 3) tags.add("灵魂探索者");

        return tags.subList(0, Math.min(tags.size(), 5));
    }

    /**
     * 基于规则生成角色名号。
     */
    private String generateRuleBasedTitle(String text) {
        if (text == null) return "记忆博物馆的守护者";
        String lower = text.toLowerCase();

        if (containsAny(lower, "夜", "星", "宇宙")) return "夜色中独行的星图收藏者";
        if (containsAny(lower, "温暖", "阳光", "热情")) return "用温度丈量世界的旅人";
        if (containsAny(lower, "安静", "内敛", "平和")) return "在寂静中聆听时光的人";
        if (containsAny(lower, "浪漫", "诗意", "花")) return "把每一刻都酿成诗的灵魂";
        if (containsAny(lower, "读书", "文学", "写作")) return "用文字重建消逝世界的人";
        if (containsAny(lower, "旅行", "远方", "流浪")) return "把足迹刻进时空的漫游者";
        if (containsAny(lower, "音乐", "旋律", "歌")) return "用音符编织记忆的乐章者";
        return "在时光长河中寻找自己的人";
    }

    /**
     * 基于规则生成角色故事。
     */
    private String generateRuleBasedStory(String text) {
        if (text == null) return "在无数个深夜，他/她把时光的碎片一片片收进记忆的博物馆。";
        String lower = text.toLowerCase();

        if (containsAny(lower, "夜", "星", "宇宙")) {
            return "在无数个深夜，他/她把星空的碎片一片片收进记忆的博物馆，等待有人来拜访。";
        }
        if (containsAny(lower, "温暖", "阳光", "热情")) {
            return "他/她用温度丈量每一段旅程，把阳光的颜色永远留在了记忆的展厅里。";
        }
        if (containsAny(lower, "安静", "内敛", "平和")) {
            return "在寂静中，他/她学会了聆听时光流逝的声音，并把它们一一记录下来。";
        }
        if (containsAny(lower, "读书", "文学", "写作")) {
            return "每一本书都是一扇门，他/她穿越其中，把那些世界的碎片带回了自己的博物馆。";
        }
        return "在时光长河中，他/她把每一个值得被记住的瞬间，都小心翼翼地收藏起来。";
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    private static String extractFirstJsonObject(String text) {
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

    private boolean isPlaceholderKey(String key) {
        return key == null || key.isBlank()
                || key.startsWith("nvapi-placeholder")
                || key.startsWith("sk-placeholder")
                || key.equals("your-api-key");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String safeText(String text) {
        if (text == null) return "";
        String t = text.length() > 800 ? text.substring(0, 800) : text;
        return t.replace("```", "ˋˋˋ");
    }

    private String validateParticleType(String type) {
        Set<String> valid = Set.of("stars", "fireflies", "snowflakes", "petals", "embers", "crystals");
        return valid.contains(type) ? type : "stars";
    }

    private String validateCoreShape(String shape) {
        Set<String> valid = Set.of("sphere", "crystal", "nebula", "prism", "torus");
        return valid.contains(shape) ? shape : "sphere";
    }

    @SuppressWarnings("unchecked")
    private String getString(Map<?, ?> map, String key, String defaultVal) {
        Object v = map.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : defaultVal;
    }

    private int getInt(Map<?, ?> map, String key, int defaultVal) {
        Object v = map.get(key);
        if (v instanceof Number n) return Math.max(300, Math.min(3000, n.intValue()));
        return defaultVal;
    }

    private double getDouble(Map<?, ?> map, String key, double defaultVal) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return defaultVal;
    }

    private boolean getBool(Map<?, ?> map, String key, boolean defaultVal) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        return defaultVal;
    }
}
