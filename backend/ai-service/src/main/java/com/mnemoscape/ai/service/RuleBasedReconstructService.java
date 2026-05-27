package com.mnemoscape.ai.service;

import com.mnemoscape.ai.model.dto.*;
import com.mnemoscape.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Rule-based scene reconstruction (formerly {@code MockReconstructService}).
 *
 * <p>这是 LLM 视觉重建之外的兜底版本：基于关键词检测 + 6 套手工调好的 SceneProfile
 * （summer/winter/night/rain/spring/autumn）输出可视化数据。它**不是** mock —
 * 而是当大模型不可用 / 超时 / 返回不合规 JSON 时确保 SceneViewer 仍能渲染的稳态降级。
 *
 * <p>场景剖面（lighting/terrain/atmosphere/objects/audio/fragments）保留英文文案
 * 是为了与 Three.js 渲染器约定的 enum 一致；前端 i18n 字典负责翻译展示。
 *
 * <p>调用入口已经统一到 {@link ReconstructDispatcher}，不再被 controller 直接依赖。
 */
@Service
public class RuleBasedReconstructService {
    private static final Logger log = LoggerFactory.getLogger(RuleBasedReconstructService.class);
    private static final int MIN_DESCRIPTION_LENGTH = 20;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final String DEFAULT_SCENE_KEY = "summer";
    private static final Map<String, SceneProfile> SCENE_PROFILES = initSceneProfiles();

    public SceneReconstructionResponse reconstruct(String description) {
        String normalized = normalizeDescription(description);
        // 描述长度过低不再硬抛 400 — 历史版会让 SceneViewer 空白；
        // 现在改为容错：太短就用一段安全占位文案补齐，依然能渲染默认夏日剖面。
        if (normalized.isBlank()) {
            normalized = "一段尚未展开的记忆，等待被时光漫游。";
        }
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            normalized = normalized.substring(0, MAX_DESCRIPTION_LENGTH);
        }

        String sceneKey = detectScene(normalized);
        SceneProfile profile = SCENE_PROFILES.getOrDefault(sceneKey, SCENE_PROFILES.get(DEFAULT_SCENE_KEY));
        Random random = new Random(seedFor(normalized, sceneKey));

        log.info("Rule-based reconstructing memory ({} chars) using {} profile", normalized.length(), profile.key());

        SceneAudioData audioData = buildAudioData(profile);
        List<SceneObject> objects = buildObjects(profile, random);
        List<SceneFragment> fragments = buildFragments(profile, random);
        SceneData sceneData = buildSceneData(profile, objects, audioData, fragments);

        SceneReconstructionResponse response = new SceneReconstructionResponse();
        response.setSceneData(sceneData);
        response.setAudioData(audioData);
        response.setEmotionVector(buildEmotionVector(profile, random));
        response.setSensoryDetails(new LinkedHashMap<>(profile.sensoryDetails()));
        response.setFragments(fragments);
        response.setSceneDataUrl(buildSceneDataUrl(sceneKey, normalized));
        return response;
    }

    public SceneEnhancementResponse enhance(SceneData sceneData) {
        SceneData normalized = normalizeSceneData(sceneData);
        SceneProfile profile = SCENE_PROFILES.get(DEFAULT_SCENE_KEY);

        List<SceneObject> objects = new ArrayList<>(normalized.getObjects());
        Set<String> existingIds = new HashSet<>();
        for (SceneObject obj : objects) {
            existingIds.add(obj.getId());
        }

        List<SceneObject> added = new ArrayList<>();
        for (SceneObjectTemplate template : profile.objects()) {
            if (added.size() >= 2) break;
            if (!existingIds.contains(template.id())) {
                SceneObject candidate = template.toSceneObject();
                objects.add(candidate);
                added.add(candidate);
            }
        }

        normalized.setObjects(objects);
        return new SceneEnhancementResponse(normalized, true, added);
    }

    public SceneGapFillResponse fillGaps(SceneData sceneData) {
        SceneData normalized = normalizeSceneData(sceneData);
        List<InferredDetail> inferred = List.of(
                new InferredDetail("detail", "A small bird nest hidden in the tree", 0.75),
                new InferredDetail("detail", "Faded footprints on the ground", 0.6)
        );
        return new SceneGapFillResponse(normalized, true, inferred);
    }

    private String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private void validateDescription(String description) {
        // 已废弃：保留方法签名以备外部调用；不再硬抛长度错误（见 reconstruct() 头部注释）。
        if (description == null) {
            throw BizException.badRequest("Description is required");
        }
    }

    private String detectScene(String description) {
        String text = description.toLowerCase(Locale.ROOT);
        if (containsAny(text, "summer", "hot", "sun", "sunny", "beach")) return "summer";
        if (containsAny(text, "winter", "snow", "cold", "ice", "frost")) return "winter";
        if (containsAny(text, "night", "evening", "moon", "star", "dark")) return "night";
        if (containsAny(text, "rain", "storm", "thunder", "drizzle")) return "rain";
        if (containsAny(text, "spring", "bloom", "flower", "garden")) return "spring";
        if (containsAny(text, "autumn", "fall", "leaf", "orange")) return "autumn";
        return DEFAULT_SCENE_KEY;
    }

    private SceneData normalizeSceneData(SceneData input) {
        if (input == null) {
            throw BizException.badRequest("sceneData is required");
        }
        SceneProfile fallback = SCENE_PROFILES.get(DEFAULT_SCENE_KEY);
        SceneData normalized = new SceneData();
        normalized.setEnvironment(Optional.ofNullable(input.getEnvironment()).orElse(fallback.environment()));
        normalized.setLighting(Optional.ofNullable(input.getLighting()).orElse(copyLighting(fallback.lighting())));
        normalized.setTerrain(Optional.ofNullable(input.getTerrain()).orElse(copyTerrain(fallback.terrain())));
        normalized.setAtmosphere(Optional.ofNullable(input.getAtmosphere()).orElse(copyAtmosphere(fallback.atmosphere())));
        normalized.setObjects(Optional.ofNullable(input.getObjects()).map(ArrayList::new).orElseGet(ArrayList::new));
        normalized.setAudioData(Optional.ofNullable(input.getAudioData()).orElseGet(() -> buildAudioData(fallback)));
        normalized.setFragments(Optional.ofNullable(input.getFragments()).map(ArrayList::new).orElseGet(ArrayList::new));
        return normalized;
    }

    private SceneData buildSceneData(SceneProfile profile, List<SceneObject> objects, SceneAudioData audioData, List<SceneFragment> fragments) {
        SceneData sceneData = new SceneData();
        sceneData.setEnvironment(profile.environment());
        sceneData.setLighting(copyLighting(profile.lighting()));
        sceneData.setTerrain(copyTerrain(profile.terrain()));
        sceneData.setAtmosphere(copyAtmosphere(profile.atmosphere()));
        sceneData.setObjects(objects);
        sceneData.setAudioData(audioData);
        sceneData.setFragments(fragments);
        return sceneData;
    }

    private List<SceneObject> buildObjects(SceneProfile profile, Random random) {
        List<SceneObject> objects = new ArrayList<>();
        for (SceneObjectTemplate template : profile.objects()) {
            SceneObject obj = template.toSceneObject();
            obj.setPosition(jitterPosition(obj.getPosition(), random));
            obj.setScale(jitterScale(obj.getScale(), random));
            objects.add(obj);
        }
        return objects;
    }

    private SceneAudioData buildAudioData(SceneProfile profile) {
        List<SceneAudioSource> ambient = new ArrayList<>();
        for (SceneAudioTemplate template : profile.ambientAudio()) {
            ambient.add(template.toSceneAudioSource());
        }
        List<SceneAudioSource> positional = new ArrayList<>();
        for (SceneAudioTemplate template : profile.positionalAudio()) {
            positional.add(template.toSceneAudioSource());
        }
        return new SceneAudioData(ambient, positional);
    }

    private Map<String, Double> buildEmotionVector(SceneProfile profile, Random random) {
        Map<String, Double> vector = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : profile.emotionBaseline().entrySet()) {
            double value = entry.getValue() + (random.nextDouble() - 0.5) * profile.emotionVariance();
            vector.put(entry.getKey(), clamp(round(value), 0.0, 1.0));
        }
        return vector;
    }

    private List<SceneFragment> buildFragments(SceneProfile profile, Random random) {
        List<String> details = new ArrayList<>(profile.forgottenDetails());
        List<String> flashbacks = new ArrayList<>(profile.emotionFlashbacks());
        List<SceneFragment> fragments = new ArrayList<>();

        int detailCount = 1 + random.nextInt(2);
        for (int i = 0; i < detailCount && !details.isEmpty(); i++) {
            String content = details.remove(random.nextInt(details.size()));
            fragments.add(new SceneFragment(
                    "forgotten_detail",
                    content,
                    new Position3d(
                            round(-5 + random.nextDouble() * 10),
                            round(random.nextDouble() * 3),
                            round(-5 + random.nextDouble() * 10)
                    ),
                    2.0,
                    false
            ));
        }

        if (!flashbacks.isEmpty()) {
            String flashback = flashbacks.get(random.nextInt(flashbacks.size()));
            fragments.add(new SceneFragment(
                    "emotion_flashback",
                    flashback,
                    new Position3d(
                            round(-5 + random.nextDouble() * 10),
                            round(1.0 + random.nextDouble() * 2),
                            round(-5 + random.nextDouble() * 10)
                    ),
                    3.0,
                    false
            ));
        }

        return fragments;
    }

    private List<Double> jitterPosition(List<Double> position, Random random) {
        double jitter = 0.35;
        return List.of(
                round(position.get(0) + (random.nextDouble() - 0.5) * jitter),
                round(position.get(1)),
                round(position.get(2) + (random.nextDouble() - 0.5) * jitter)
        );
    }

    private List<Double> jitterScale(List<Double> scale, Random random) {
        double jitter = 0.08;
        double factor = 1 + (random.nextDouble() - 0.5) * jitter;
        return List.of(
                round(scale.get(0) * factor),
                round(scale.get(1) * factor),
                round(scale.get(2) * factor)
        );
    }

    private SceneLighting copyLighting(SceneLighting lighting) {
        return new SceneLighting(lighting.getType(), lighting.getColor(), lighting.getIntensity());
    }

    private SceneTerrain copyTerrain(SceneTerrain terrain) {
        return new SceneTerrain(terrain.getType(), terrain.getColor());
    }

    private SceneAtmosphere copyAtmosphere(SceneAtmosphere atmosphere) {
        return new SceneAtmosphere(atmosphere.getFogColor(), atmosphere.getFogDensity(), atmosphere.getBackgroundColor());
    }

    private String buildSceneDataUrl(String sceneKey, String description) {
        UUID stableId = UUID.nameUUIDFromBytes((sceneKey + ":" + description).getBytes(StandardCharsets.UTF_8));
        return "scene://rule/" + stableId;
    }

    private long seedFor(String description, String sceneKey) {
        return Objects.hash(description.toLowerCase(Locale.ROOT), sceneKey);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static Map<String, SceneProfile> initSceneProfiles() {
        Map<String, SceneProfile> profiles = new LinkedHashMap<>();
        profiles.put("summer", new SceneProfile(
                "summer",
                "outdoor_courtyard",
                new SceneLighting("warm_sunset", "#FFD700", 0.9),
                new SceneTerrain("flat", "#556B2F"),
                new SceneAtmosphere("#FFE4B5", 0.001, "#87CEEB"),
                List.of(
                        new SceneObjectTemplate("tree_1", "tree", List.of(0.0, 0.0, -5.0), "#228B22", "large osmanthus tree"),
                        new SceneObjectTemplate("bench_1", "bench", List.of(0.0, 0.0, -2.0), "#8B4513", "wooden bench"),
                        new SceneObjectTemplate("house_1", "house", List.of(-5.0, 0.0, 3.0), "#DEB887", "traditional house"),
                        new SceneObjectTemplate("lamp_1", "lamp", List.of(0.0, 2.5, -3.0), "#FFD700", "warm lantern")
                ),
                List.of(
                        new SceneAudioTemplate("cricket", "/audio/cicada.mp3", 0.5, true, null),
                        new SceneAudioTemplate("wind_breeze", "/audio/breeze.mp3", 0.2, true, null)
                ),
                List.of(
                        new SceneAudioTemplate("leaves_rustle", "/audio/leaves.mp3", 0.3, null, List.of(0.0, 1.5, -4.0))
                ),
                emotionBaseline(0.75, 0.12, 0.08, 0.05, 0.18, 0.55, 0.6, 0.2),
                0.18,
                Map.of(
                        "visual", "金色阳光洒满院落，绿意层叠，光影斑驳如画。",
                        "auditory", "蝉鸣层层叠叠，温热的风掠过树梢。",
                        "olfactory", "晒暖的木头混着花草的清香在空气里浮动。",
                        "tactile", "微温的暑气贴在皮肤上，被一阵风轻轻掀走。"
                ),
                List.of(
                        "枝丫深处藏着一个小小的鸟巢",
                        "树干上有人刻下的、已经褪色的姓名缩写",
                        "被遗忘的玩具半埋在温热的泥土里",
                        "长椅背后塞着一张旧照片",
                        "阴影角落里悄悄长出几朵野菇"
                ),
                List.of(
                        "一阵温暖与安全感忽然将你包裹",
                        "你心底掠过一丝甜中带苦的怀念",
                        "童年里某个纯粹的快乐瞬间一闪而过",
                        "那个静谧的瞬间又短暂地回到身边"
                )
        ));
        profiles.put("winter", new SceneProfile(
                "winter",
                "snowy_landscape",
                new SceneLighting("diffuse_winter", "#E0E8F0", 0.7),
                new SceneTerrain("snow", "#FFFFFF"),
                new SceneAtmosphere("#D0D8E0", 0.003, "#C0C8D0"),
                List.of(
                        new SceneObjectTemplate("tree_1", "bare_tree", List.of(0.0, 0.0, -5.0), "#654321", "bare winter tree"),
                        new SceneObjectTemplate("snowman", "sphere", List.of(2.0, 0.0, -3.0), "#FFFFFF", "snowman"),
                        new SceneObjectTemplate("house_1", "house", List.of(-4.0, 0.0, 3.0), "#8B4513", "cozy house")
                ),
                List.of(
                        new SceneAudioTemplate("wind_cold", "/audio/wind.mp3", 0.6, true, null)
                ),
                List.of(
                        new SceneAudioTemplate("snow_crunch", "/audio/snow.mp3", 0.2, null, List.of(0.0, 0.0, 0.0))
                ),
                emotionBaseline(0.45, 0.35, 0.08, 0.15, 0.12, 0.6, 0.4, 0.55),
                0.2,
                Map.of(
                        "visual", "色调被霜气调淡，远处铺着一层柔蓝的阴影。",
                        "auditory", "风声很轻，雪在脚下发出闷闷的沙沙声。",
                        "olfactory", "凛冽的空气里带着松针与干净的冰意。",
                        "tactile", "冷风轻咬指尖，呼出的白气在面前慢慢散开。"
                ),
                List.of(
                        "玻璃窗上结着精细的冰花",
                        "长椅上忘了拿走的羊毛围巾",
                        "雪人旁延伸出去的一串靴印",
                        "苍白天光下闪着光的冰柱"
                ),
                List.of(
                        "胸口涌起一阵静默的安宁",
                        "雪地里似乎传来某段久远的笑声",
                        "那种新雪初落时特有的安心感又回来了"
                )
        ));
        profiles.put("night", new SceneProfile(
                "night",
                "night_courtyard",
                new SceneLighting("moonlight", "#4A6FA5", 0.4),
                new SceneTerrain("flat", "#1a1a2e"),
                new SceneAtmosphere("#0a0a1a", 0.005, "#0a0a1a"),
                List.of(
                        new SceneObjectTemplate("tree_1", "tree", List.of(0.0, 0.0, -5.0), "#1a3a1a", "dark tree silhouette"),
                        new SceneObjectTemplate("house_1", "house", List.of(-4.0, 0.0, 3.0), "#4a3a2a", "house with lit window"),
                        new SceneObjectTemplate("star_1", "particle", List.of(0.0, 5.0, -10.0), "#FFFFFF", "twinkling stars")
                ),
                List.of(
                        new SceneAudioTemplate("cricket_night", "/audio/cricket_night.mp3", 0.4, true, null),
                        new SceneAudioTemplate("owl", "/audio/owl.mp3", 0.15, false, null)
                ),
                List.of(),
                emotionBaseline(0.4, 0.25, 0.05, 0.1, 0.18, 0.7, 0.55, 0.35),
                0.16,
                Map.of(
                        "visual", "月光把整个院落染成深邃的蓝。",
                        "auditory", "夜虫与远处偶尔的鸣叫为寂静打着节拍。",
                        "olfactory", "凉意里有石头和湿润泥土的气息。",
                        "tactile", "每一次呼吸都像被夜色轻轻包覆。"
                ),
                List.of(
                        "远处那扇窗里有一束灯光在闪烁",
                        "你小时候反复描绘过的那几颗星座",
                        "石阶上传来的、轻轻的脚步回响"
                ),
                List.of(
                        "一种安静的温柔把你整个人裹住",
                        "你感到一种与某个熟悉夜晚同源的安心"
                )
        ));
        profiles.put("rain", new SceneProfile(
                "rain",
                "rainy_street",
                new SceneLighting("overcast", "#808890", 0.5),
                new SceneTerrain("wet", "#4a5a4a"),
                new SceneAtmosphere("#708090", 0.008, "#606870"),
                List.of(
                        new SceneObjectTemplate("tree_1", "tree", List.of(0.0, 0.0, -5.0), "#2a4a2a", "wet tree"),
                        new SceneObjectTemplate("puddle_1", "plane", List.of(0.0, 0.01, 0.0), "#4a6a8a", "reflective puddle"),
                        new SceneObjectTemplate("umbrella", "umbrella", List.of(0.0, 2.0, 0.0), "#E74C3C", "red umbrella")
                ),
                List.of(
                        new SceneAudioTemplate("rainfall", "/audio/rain.mp3", 0.7, true, null)
                ),
                List.of(
                        new SceneAudioTemplate("thunder", "/audio/thunder.mp3", 0.3, null, List.of(10.0, 5.0, -10.0))
                ),
                emotionBaseline(0.35, 0.45, 0.08, 0.2, 0.18, 0.5, 0.42, 0.45),
                0.22,
                Map.of(
                        "visual", "雨丝把街景晕成淡淡的水彩，水洼里映出反光。",
                        "auditory", "稳定的雨声里夹着远方低沉的雷鸣。",
                        "olfactory", "雨腥味与湿润的泥土气一同涌起。",
                        "tactile", "凉雾轻拂面颊，雨点落在身侧噗噗作响。"
                ),
                List.of(
                        "一道闷雷在远处缓缓滚过",
                        "雨滴在水洼表面荡出层层涟漪",
                        "孤零零的路灯发出轻微的电流声"
                ),
                List.of(
                        "熟悉的雨势带来一种安静的慰藉",
                        "你想起某次共撑一把伞穿过雨幕的瞬间"
                )
        ));
        profiles.put("spring", new SceneProfile(
                "spring",
                "flower_garden",
                new SceneLighting("morning_sun", "#FFE4B5", 0.8),
                new SceneTerrain("grass", "#90EE90"),
                new SceneAtmosphere("#FFE4E1", 0.001, "#B0E0E6"),
                List.of(
                        new SceneObjectTemplate("tree_1", "cherry_tree", List.of(0.0, 0.0, -5.0), "#FFB7C5", "blooming cherry tree"),
                        new SceneObjectTemplate("flower_1", "flower", List.of(2.0, 0.0, -1.0), "#FF69B4", "pink flowers"),
                        new SceneObjectTemplate("bench_1", "bench", List.of(-2.0, 0.0, 0.0), "#8B7355", "garden bench")
                ),
                List.of(
                        new SceneAudioTemplate("birds", "/audio/birds.mp3", 0.3, true, null)
                ),
                List.of(
                        new SceneAudioTemplate("wind_gentle", "/audio/wind_soft.mp3", 0.2, true, null)
                ),
                emotionBaseline(0.7, 0.15, 0.05, 0.08, 0.2, 0.55, 0.65, 0.25),
                0.15,
                Map.of(
                        "visual", "粉嫩花朵铺开，光线柔和得像被滤过一层纱。",
                        "auditory", "鸟鸣与微风交织在空气里。",
                        "olfactory", "新鲜的花香与带露水的青草气交错。",
                        "tactile", "清晨的微凉穿过指缝，又被树影抚平。"
                ),
                List.of(
                        "几片花瓣被风裹着飞过身边",
                        "长椅扶手上系着的一条丝带",
                        "松软泥土上一串新鲜的脚印"
                ),
                List.of(
                        "随着阳光升起，希望也悄悄涨起来",
                        "你心底涌起一股关于「开始」的雀跃"
                )
        ));
        profiles.put("autumn", new SceneProfile(
                "autumn",
                "autumn_path",
                new SceneLighting("golden_hour", "#FF8C00", 0.85),
                new SceneTerrain("leaves", "#8B4513"),
                new SceneAtmosphere("#FFD700", 0.002, "#FF6347"),
                List.of(
                        new SceneObjectTemplate("tree_1", "autumn_tree", List.of(0.0, 0.0, -5.0), "#FF4500", "orange maple tree"),
                        new SceneObjectTemplate("leaf_pile", "pile", List.of(2.0, 0.0, 0.0), "#8B4513", "pile of fallen leaves"),
                        new SceneObjectTemplate("house_1", "house", List.of(-4.0, 0.0, 3.0), "#DEB887", "house with chimney")
                ),
                List.of(
                        new SceneAudioTemplate("wind_gentle", "/audio/wind_soft.mp3", 0.2, true, null)
                ),
                List.of(
                        new SceneAudioTemplate("leaves_rustle", "/audio/leaves.mp3", 0.25, null, List.of(-1.0, 0.5, -2.0))
                ),
                emotionBaseline(0.55, 0.4, 0.08, 0.1, 0.18, 0.65, 0.5, 0.5),
                0.2,
                Map.of(
                        "visual", "金色光线从沙沙作响的叶隙间漏下。",
                        "auditory", "干燥的落叶在微风里发出细碎的脆响。",
                        "olfactory", "空气里飘着一丝木柴烟与凉爽的味道。",
                        "tactile", "凉风预告着即将到来的更冷的日子。"
                ),
                List.of(
                        "一片叶子从树冠缓缓盘旋落下",
                        "屋内透出的暖光从窗里漫开",
                        "脚下传来落叶被踩碎的轻响"
                ),
                List.of(
                        "某个秋夜的回忆若有似无地回到心里",
                        "你心底浮起一种温和的、接受一切的平静"
                )
        ));
        return Collections.unmodifiableMap(profiles);
    }

    private static Map<String, Double> emotionBaseline(double joy, double sadness, double anger, double fear,
                                                       double surprise, double nostalgia, double peace, double melancholy) {
        Map<String, Double> baseline = new LinkedHashMap<>();
        baseline.put("joy", joy);
        baseline.put("sadness", sadness);
        baseline.put("anger", anger);
        baseline.put("fear", fear);
        baseline.put("surprise", surprise);
        baseline.put("nostalgia", nostalgia);
        baseline.put("peace", peace);
        baseline.put("melancholy", melancholy);
        return baseline;
    }

    private record SceneProfile(
            String key,
            String environment,
            SceneLighting lighting,
            SceneTerrain terrain,
            SceneAtmosphere atmosphere,
            List<SceneObjectTemplate> objects,
            List<SceneAudioTemplate> ambientAudio,
            List<SceneAudioTemplate> positionalAudio,
            Map<String, Double> emotionBaseline,
            double emotionVariance,
            Map<String, String> sensoryDetails,
            List<String> forgottenDetails,
            List<String> emotionFlashbacks
    ) {
    }

    private record SceneObjectTemplate(
            String id,
            String type,
            List<Double> position,
            String color,
            String name
    ) {
        public SceneObject toSceneObject() {
            return new SceneObject(id, type, name, new ArrayList<>(position), color, List.of(1.0, 1.0, 1.0));
        }
    }

    private record SceneAudioTemplate(
            String type,
            String file,
            double volume,
            Boolean loop,
            List<Double> position
    ) {
        public SceneAudioSource toSceneAudioSource() {
            return new SceneAudioSource(type, file, volume, loop, position == null ? null : new ArrayList<>(position));
        }
    }
}
