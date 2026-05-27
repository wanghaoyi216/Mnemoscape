package com.mnemoscape.ai.service;

import com.mnemoscape.ai.model.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 场景重建分发器：把 controller 与具体实现解耦，并在 LLM / 规则版之间做策略路由。
 *
 * <p>路由模式（{@code mnemoscape.ai.reconstruct.mode}）：
 * <ul>
 *   <li>{@code auto}（默认）：先尝试 {@link LlmReconstructService}，任何异常 → 透明降级到 {@link RuleBasedReconstructService}</li>
 *   <li>{@code llm}：强制走 LLM；失败直接抛出（用于调试）</li>
 *   <li>{@code rule}：强制走规则版（用于离线 / 无 key 环境）</li>
 * </ul>
 *
 * <p>当 LLM 成功时，dispatcher 把 LLM 的"内容创意层"（场景名 / 物体 / 中文片段 / 感官 / 情绪向量）
 * 与规则版的"管线壳"（声音模板 / 默认值兜底）合并成完整的 {@link SceneReconstructionResponse}。
 * 这样：
 * <ol>
 *   <li>渲染器需要的 audioData 总有值（avoid 360° 静音）</li>
 *   <li>LLM 输出局部缺字段时不会让 SceneViewer 崩</li>
 *   <li>规则版仍然作为唯一的"必定可用兜底"</li>
 * </ol>
 */
@Service
public class ReconstructDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReconstructDispatcher.class);

    public enum Mode { AUTO, LLM, RULE }

    private final RuleBasedReconstructService ruleService;
    private final LlmReconstructService llmService;
    private final Mode mode;

    public ReconstructDispatcher(RuleBasedReconstructService ruleService,
                                  LlmReconstructService llmService,
                                  @Value("${mnemoscape.ai.reconstruct.mode:auto}") String modeStr) {
        this.ruleService = ruleService;
        this.llmService = llmService;
        this.mode = parseMode(modeStr);
        log.info("[ReconstructDispatcher] mode={}", this.mode);
    }

    /** 历史签名：仅描述。保留兼容旧调用方（如 memory-service 老路径）。 */
    public SceneReconstructionResponse reconstruct(String description) {
        ReconstructRequest req = new ReconstructRequest(description);
        return reconstruct(req);
    }

    /** 新签名：完整 ReconstructRequest，携带年/季节/时段/地点/标题等结构化上下文。 */
    public SceneReconstructionResponse reconstruct(ReconstructRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("ReconstructRequest is null");
        }
        String description = req.getDescription();
        if (mode == Mode.RULE) {
            return ruleService.reconstruct(description);
        }
        try {
            LlmReconstructService.LlmSceneSketch sketch = llmService.sketch(req);
            return mergeWithRuleBaseline(sketch, description);
        } catch (Exception e) {
            if (mode == Mode.LLM) {
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
            log.warn("[ReconstructDispatcher] LLM reconstruct failed; degrading to rule-based: {}",
                    e.getMessage());
            return ruleService.reconstruct(description);
        }
    }

    public SceneEnhancementResponse enhance(SceneData sceneData) {
        // enhance / fillGaps 仍走规则版 — 它们只是给现有 scene 补元素，
        // LLM 介入收益不大但首字延迟很高
        return ruleService.enhance(sceneData);
    }

    public SceneGapFillResponse fillGaps(SceneData sceneData) {
        return ruleService.fillGaps(sceneData);
    }

    /* ------------------------------------------------------------------ */

    /**
     * 把 LLM 草图与规则版同主题剖面合并：
     * - 环境/光线/地形/氛围/对象/片段/感官/情绪向量 → 优先用 LLM 输出
     * - 缺什么字段 → 用规则版同 sceneKey 的剖面值兜底
     * - audioData → 一律用规则版（LLM 不擅长生成稳定的音频路径）
     */
    private SceneReconstructionResponse mergeWithRuleBaseline(LlmReconstructService.LlmSceneSketch sketch,
                                                              String description) {
        // 先跑规则版拿"管线壳"
        SceneReconstructionResponse fallback = ruleService.reconstruct(description);

        // 复制规则版骨架，再用 LLM 字段覆盖
        SceneData base = fallback.getSceneData();
        SceneData out = new SceneData();
        out.setEnvironment(nonBlank(sketch.environment, base.getEnvironment()));
        out.setLighting(buildLighting(sketch, base.getLighting()));
        out.setTerrain(buildTerrain(sketch, base.getTerrain()));
        out.setAtmosphere(buildAtmosphere(sketch, base.getAtmosphere()));
        out.setObjects(buildObjects(sketch.objects, base.getObjects()));
        out.setAudioData(base.getAudioData());

        List<SceneFragment> fragments = buildFragments(sketch.fragments, base.getFragments());
        out.setFragments(fragments);

        SceneReconstructionResponse merged = new SceneReconstructionResponse();
        merged.setSceneData(out);
        merged.setAudioData(base.getAudioData());
        merged.setEmotionVector(buildEmotionVector(sketch.emotionVector, fallback.getEmotionVector()));
        merged.setSensoryDetails(buildSensoryDetails(sketch.sensoryDetails, fallback.getSensoryDetails()));
        merged.setFragments(fragments);
        merged.setSceneDataUrl(LlmReconstructService.buildSceneDataUrl(description));
        return merged;
    }

    private SceneLighting buildLighting(LlmReconstructService.LlmSceneSketch s, SceneLighting fb) {
        return new SceneLighting(
                nonBlank(s.lightingType, fb == null ? "ambient" : fb.getType()),
                nonBlank(s.lightingColor, fb == null ? "#ffffff" : fb.getColor()),
                s.lightingIntensity != null ? clamp(s.lightingIntensity, 0.1, 1.0)
                                            : (fb == null ? 0.7 : fb.getIntensity()));
    }

    private SceneTerrain buildTerrain(LlmReconstructService.LlmSceneSketch s, SceneTerrain fb) {
        return new SceneTerrain(
                nonBlank(s.terrainType, fb == null ? "flat" : fb.getType()),
                nonBlank(s.terrainColor, fb == null ? "#556b2f" : fb.getColor()));
    }

    private SceneAtmosphere buildAtmosphere(LlmReconstructService.LlmSceneSketch s, SceneAtmosphere fb) {
        return new SceneAtmosphere(
                nonBlank(s.fogColor, fb == null ? "#cccccc" : fb.getFogColor()),
                s.fogDensity != null ? clamp(s.fogDensity, 0.0, 0.02)
                                     : (fb == null ? 0.002 : fb.getFogDensity()),
                nonBlank(s.backgroundColor, fb == null ? "#87ceeb" : fb.getBackgroundColor()));
    }

    private List<SceneObject> buildObjects(List<LlmReconstructService.LlmObject> sketchObjs,
                                           List<SceneObject> fbObjs) {
        if (sketchObjs == null || sketchObjs.isEmpty()) return fbObjs == null ? List.of() : fbObjs;
        List<SceneObject> out = new ArrayList<>();
        int i = 0;
        for (LlmReconstructService.LlmObject o : sketchObjs) {
            if (o == null) continue;
            String id = nonBlank(o.id, "obj_" + (i++));
            String type = nonBlank(o.type, "sphere");
            String name = nonBlank(o.name, "对象");
            List<Double> pos = sanitizePosition(o.position);
            String color = nonBlank(o.color, "#94a3b8");
            out.add(new SceneObject(id, type, name, pos, color, List.of(1.0, 1.0, 1.0)));
        }
        return out;
    }

    private List<SceneFragment> buildFragments(List<LlmReconstructService.LlmFragment> sketchFrags,
                                                List<SceneFragment> fbFrags) {
        if (sketchFrags == null || sketchFrags.isEmpty()) return fbFrags == null ? List.of() : fbFrags;
        List<SceneFragment> out = new ArrayList<>();
        for (LlmReconstructService.LlmFragment f : sketchFrags) {
            if (f == null || f.content == null || f.content.isBlank()) continue;
            String type = "emotion_flashback".equalsIgnoreCase(f.type)
                    ? "emotion_flashback" : "forgotten_detail";
            List<Double> pos = sanitizePosition(f.position);
            out.add(new SceneFragment(type, f.content.trim(),
                    new Position3d(pos.get(0), pos.get(1), pos.get(2)),
                    "emotion_flashback".equals(type) ? 3.0 : 2.0,
                    false));
        }
        return out;
    }

    private Map<String, String> buildSensoryDetails(Map<String, String> sketch, Map<String, String> fb) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] keys = {"visual", "auditory", "olfactory", "tactile"};
        for (String k : keys) {
            String v = sketch == null ? null : sketch.get(k);
            if (v == null || v.isBlank()) v = fb == null ? null : fb.get(k);
            if (v != null) out.put(k, v);
        }
        return out;
    }

    private Map<String, Double> buildEmotionVector(Map<String, Double> sketch, Map<String, Double> fb) {
        Map<String, Double> out = new LinkedHashMap<>();
        String[] keys = {"joy", "sadness", "anger", "fear", "surprise", "nostalgia", "peace", "melancholy"};
        for (String k : keys) {
            Double v = sketch == null ? null : sketch.get(k);
            if (v == null && fb != null) v = fb.get(k);
            if (v == null) v = 0.0;
            out.put(k, clamp(v, 0.0, 1.0));
        }
        return out;
    }

    private static List<Double> sanitizePosition(List<Double> pos) {
        if (pos == null || pos.size() < 3) return List.of(0.0, 0.0, -3.0);
        double x = clamp(pos.get(0) == null ? 0.0 : pos.get(0), -8.0, 8.0);
        double y = clamp(pos.get(1) == null ? 0.0 : pos.get(1), 0.0, 5.0);
        double z = clamp(pos.get(2) == null ? -3.0 : pos.get(2), -8.0, 8.0);
        return List.of(round(x), round(y), round(z));
    }

    private static String nonBlank(String value, String fb) {
        return (value == null || value.isBlank()) ? fb : value;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Mode parseMode(String s) {
        if (s == null) return Mode.AUTO;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "llm" -> Mode.LLM;
            case "rule", "rules", "rule-based", "ruleset" -> Mode.RULE;
            default -> Mode.AUTO;
        };
    }
}
