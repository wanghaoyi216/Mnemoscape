package com.mnemoscape.ai.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.ai.model.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 驱动的场景重建（v1）。
 *
 * <p>当用户写下中文记忆描述时，让基座模型输出一份"专门面向 Three.js 渲染器"
 * 的结构化 JSON：环境名、光线/地形/氛围色调、若干 SceneObject、3D 空间里的
 * 中文 fragments（被遗忘的细节 / 情感闪回）、感官描述。
 *
 * <p>这一层<b>只负责</b>把"自由文本 → 高质量结构化 SceneData"。声音/情绪向量/
 * sceneDataUrl 都由 {@link RuleBasedReconstructService} 在 dispatcher 层补齐 —
 * 让规则版当作"管线兜底"，LLM 只换最有价值的"内容创作"环节。
 *
 * <p>失败语义（fail-fast，由 dispatcher 兜底降级）：
 * <ul>
 *   <li>占位 key / 缺 key → 抛 {@link AiUpstreamException(MISSING_KEY)}</li>
 *   <li>模型输出不是合法 JSON / 字段缺失过多 → 抛 {@link IllegalStateException}</li>
 *   <li>上游超时 / 5xx → 由 ChatClient 抛 RuntimeException，由 dispatcher 转规则版</li>
 * </ul>
 */
@Service
public class LlmReconstructService {

    private static final Logger log = LoggerFactory.getLogger(LlmReconstructService.class);

    /** 模型输出的"创意内容"形状。dispatcher 会把它和规则版的"基础壳"合并成 SceneReconstructionResponse。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LlmSceneSketch {
        public String environment;          // e.g. "outdoor_courtyard" / "rainy_street"
        public String lightingType;         // 与规则版枚举对齐："warm_sunset"/"moonlight"/...
        public String lightingColor;        // hex
        public Double lightingIntensity;    // 0.2 .. 1.0
        public String terrainType;          // "flat"/"snow"/"wet"/"grass"/"leaves"
        public String terrainColor;         // hex
        public String backgroundColor;      // hex
        public String fogColor;             // hex
        public Double fogDensity;           // 0..0.01
        public List<LlmObject> objects;     // 4-8 件
        public List<LlmFragment> fragments; // 3-5 条中文短句
        public Map<String, String> sensoryDetails; // visual/auditory/olfactory/tactile
        public Map<String, Double> emotionVector;  // joy/sadness/.../melancholy 0..1
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LlmObject {
        public String id;        // 任意稳定字符串
        public String type;      // 与渲染器枚举对齐：tree/house/bench/lamp/sphere/cylinder/cone/plane/flower/...
        public String name;      // 中文人类可读名（"奶奶的木椅"）
        public List<Double> position; // [x,y,z]
        public String color;     // hex
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LlmFragment {
        public String type;      // forgotten_detail / emotion_flashback
        public String content;   // 中文短句
        public List<Double> position; // [x,y,z]
    }

    private final ChatClient chatClient;
    private final AiUpstreamProperties props;
    private final String configuredApiKey;
    private final ObjectMapper json = new ObjectMapper();

    public LlmReconstructService(@Qualifier("mnemoscapeChatClientBuilder") ChatClient.Builder builder,
                                 AiUpstreamProperties props,
                                 @Value("${spring.ai.openai.api-key:}") String apiKey) {
        // 重建场景与对话使用同一个基座（M2.7），但**不**带 chat 工具集 —
        // 这里希望模型聚焦在 JSON 输出，避免它顺手调 milvusSearchTool 之类的拖慢响应
        this.chatClient = builder.build();
        this.props = props;
        this.configuredApiKey = apiKey;
    }

    /**
     * 从用户中文描述生成 LlmSceneSketch；调用方负责合并到完整 SceneReconstructionResponse。
     * @throws AiUpstreamException 若占位 key / 没设 key
     * @throws RuntimeException    上游失败 / 解析失败（让 dispatcher 走规则版降级）
     */
    /**
     * 历史签名：仅描述。保留兼容旧调用方。
     * @throws AiUpstreamException 若占位 key / 没设 key
     * @throws RuntimeException    上游失败 / 解析失败（让 dispatcher 走规则版降级）
     */
    public LlmSceneSketch sketch(String description) {
        ReconstructRequest req = new ReconstructRequest(description);
        return sketch(req);
    }

    /** 新签名：让 LLM 看到完整结构化上下文（年/季节/时段/地点/标题），输出更 grounded。 */
    public LlmSceneSketch sketch(ReconstructRequest req) {
        ensureRealKeyOrThrow();
        String prompt = buildPrompt(req);

        long t0 = System.currentTimeMillis();
        String raw = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - t0;

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned empty content");
        }

        String jsonStr = extractFirstJsonObject(raw);
        if (jsonStr == null) {
            throw new IllegalStateException(
                    "LLM did not return parseable JSON; head=" + abbrev(raw, 220));
        }

        try {
            LlmSceneSketch sketch = json.readValue(jsonStr, LlmSceneSketch.class);
            log.info("[LlmReconstruct] elapsedMs={} envSet={} objects={} fragments={}",
                    elapsed,
                    sketch.environment != null,
                    sketch.objects == null ? 0 : sketch.objects.size(),
                    sketch.fragments == null ? 0 : sketch.fragments.size());
            // 至少需要 environment + objects，否则不算成功
            if (sketch.environment == null || sketch.environment.isBlank()
                    || sketch.objects == null || sketch.objects.isEmpty()) {
                throw new IllegalStateException("LLM JSON missing required fields (environment/objects)");
            }
            return sketch;
        } catch (com.fasterxml.jackson.core.JacksonException pe) {
            throw new IllegalStateException("LLM JSON parse failed: " + pe.getMessage()
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
                    "NVIDIA_API_KEY is not configured; LLM reconstruction cannot run.");
        }
    }

    private String buildPrompt(ReconstructRequest req) {
        String description = req.getDescription();
        StringBuilder ctx = new StringBuilder();
        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            ctx.append("- 标题：").append(req.getTitle()).append('\n');
        }
        if (req.getMemoryYear() != null) {
            ctx.append("- 年份：").append(req.getMemoryYear()).append('\n');
        }
        if (req.getMemorySeason() != null && !req.getMemorySeason().isBlank()) {
            ctx.append("- 季节：").append(req.getMemorySeason()).append('\n');
        }
        if (req.getMemoryTimeOfDay() != null && !req.getMemoryTimeOfDay().isBlank()) {
            ctx.append("- 时段：").append(req.getMemoryTimeOfDay()).append('\n');
        }
        if (req.getMemoryLocation() != null && !req.getMemoryLocation().isBlank()) {
            ctx.append("- 地点：").append(req.getMemoryLocation()).append('\n');
        }
        String contextSection = ctx.length() == 0 ? "" : ("结构化上下文（请遵循）：\n" + ctx + "\n");

        return """
            你是 Mnemoscape 的场景重建引擎。把用户写下的中文记忆描述转成一份严格 JSON，
            供 Three.js 渲染器直接消费。**你输出的所有具象内容（objects.name / fragments.content /
            sensoryDetails）必须紧扣用户原文里出现的人物、物件、地点、动作、情绪、季节，
            禁止生造与原文无关的元素**。仅输出 JSON 对象，前后不要有任何说明文字。
            
            JSON 字段要求（以下字段必须出现）：
            - environment: 蛇形命名英文环境名（仅可选用："outdoor_courtyard","snowy_landscape",
              "night_courtyard","rainy_street","flower_garden","autumn_path","indoor_room",
              "city_street","seaside","mountain_path","schoolyard","kitchen"）；
              **必须与下面的"结构化上下文"中的季节/时段/地点保持一致**（例如季节=WINTER 应选
              snowy_landscape，时段=NIGHT 应选 night_courtyard）。
            - lightingType: 必须从 ["warm_sunset","diffuse_winter","moonlight","overcast",
              "morning_sun","golden_hour","ambient","candlelight"] 中选一；同样需匹配上下文时段。
            - lightingColor: 6 位 hex（如 "#FFD700"）
            - lightingIntensity: 0.2~1.0 之间的小数
            - terrainType: 必须从 ["flat","snow","wet","grass","leaves","sand","stone","wood"] 中选一
            - terrainColor: 6 位 hex
            - backgroundColor: 6 位 hex
            - fogColor: 6 位 hex
            - fogDensity: 0~0.01 之间小数
            - objects: 长度 4~8 的数组，每项 {id,type,name,position:[x,y,z],color}；
              type 仅可选用 ["tree","house","bench","lamp","sphere","cylinder","cone","plane",
              "flower","pile","bare_tree","umbrella","autumn_tree","cherry_tree","particle"]；
              name 必须中文，且**必须是用户描述里出现过的具体物件 / 自然元素**（如"奶奶的木椅"、
              "海边的灯塔"、"阳台上的茉莉"）；如果某类元素需要表达但描述里没有名字，
              用 type 的中文（如"灯"、"长椅"）即可；不要套用与描述无关的"夏日庭院常见物"。
              position 取值范围 -6 ≤ x,z ≤ 6, 0 ≤ y ≤ 3
            - fragments: 长度 3~5 的数组，每项 {type,content,position:[x,y,z]}；
              type 仅可选用 ["forgotten_detail","emotion_flashback"]
                  • forgotten_detail = 用户描述里**真实写到的细节**（不是模板套话）
                  • emotion_flashback = 用户描述里**真实流露的情感瞬间**（紧扣原文情绪）
              content 必须是简短中文短句，<= 30 个汉字；**严禁出现"长椅背后的旧照片"
              "枝丫深处的鸟巢"等套模板的话术，除非用户原文里恰好提到了这些**。
              position 同上
            - sensoryDetails: 必须包含 keys ["visual","auditory","olfactory","tactile"]，
              每个 value 是中文短句，且**直接引用用户原文里能感知到的细节**；
              用户没写到的感官（例如原文只谈视觉）→ value 可以写成"未着墨"，不要生造。
            - emotionVector: 必须包含 keys ["joy","sadness","anger","fear","surprise",
              "nostalgia","peace","melancholy"]，每个值 0~1 的小数，按用户原文情绪分布给分。
            
            禁止：
            - 不要输出 markdown / code fence
            - 不要给字段加注释
            - 不要省略上述字段；缺失时用合理默认值填，但仍必须存在
            - 不要套用任何"通用记忆模板"（旧照片、风铃、童年笑声…除非原文真有）
            
            """ + contextSection + """
            用户记忆描述如下：
            ---
            """ + safeDescription(description) + """
            ---
            
            现在仅输出符合上述 schema 且 grounded 在原文的 JSON 对象。
            """;
    }

    /** 从 LLM 输出里抽出第一段平衡的 {...} JSON（容错 LLM 偶尔把 JSON 包在 markdown / 解释文字里）。 */
    private static String extractFirstJsonObject(String text) {
        // 优先尝试直接找 ```json ... ``` 围栏
        Matcher fence = Pattern.compile("```(?:json)?\\s*\\n([\\s\\S]*?)\\n```").matcher(text);
        if (fence.find()) {
            String inner = fence.group(1).trim();
            if (inner.startsWith("{") && inner.endsWith("}")) return inner;
        }
        // 否则按花括号配对扫描
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

    private static String safeDescription(String description) {
        if (description == null) return "";
        String trimmed = description.length() > 1500 ? description.substring(0, 1500) : description;
        return trimmed.replace("```", "ˋˋˋ"); // 防止用户文本里嵌 ``` 干扰围栏检测
    }

    private static String abbrev(String s, int n) {
        if (s == null) return "null";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    /** 提供给 dispatcher 在 buildSceneDataUrl 时复用，让 LLM 输出与规则版同样可缓存。 */
    public static String buildSceneDataUrl(String description) {
        UUID id = UUID.nameUUIDFromBytes(("llm:" + (description == null ? "" : description))
                .getBytes(StandardCharsets.UTF_8));
        return "scene://llm/" + id;
    }
}
