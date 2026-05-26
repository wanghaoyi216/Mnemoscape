package com.mnemoscape.ai.service;

import com.mnemoscape.ai.model.dto.*;
import com.mnemoscape.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MockReconstructService {
    private static final Logger log = LoggerFactory.getLogger(MockReconstructService.class);
    private static final int MIN_DESCRIPTION_LENGTH = 20;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final String DEFAULT_SCENE_KEY = "summer";
    private static final Map<String, SceneProfile> SCENE_PROFILES = initSceneProfiles();

    public SceneReconstructionResponse reconstruct(String description) {
        String normalized = normalizeDescription(description);
        validateDescription(normalized);

        String sceneKey = detectScene(normalized);
        SceneProfile profile = SCENE_PROFILES.getOrDefault(sceneKey, SCENE_PROFILES.get(DEFAULT_SCENE_KEY));
        Random random = new Random(seedFor(normalized, sceneKey));

        log.info("Mock reconstructing memory ({} chars) using {} profile", normalized.length(), profile.key());

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
        if (description.isBlank()) {
            throw BizException.badRequest("Description is required");
        }
        if (description.length() < MIN_DESCRIPTION_LENGTH || description.length() > MAX_DESCRIPTION_LENGTH) {
            throw BizException.badRequest("Description must be between " + MIN_DESCRIPTION_LENGTH + " and " + MAX_DESCRIPTION_LENGTH + " characters");
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
        return "scene://mock/" + stableId;
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
                        "visual", "A beautifully rendered summer courtyard with golden light and lush greenery",
                        "auditory", "Layered cicadas and warm breezes fill the space",
                        "olfactory", "Sun-warmed wood and flowering herbs linger in the air",
                        "tactile", "Soft heat settles on your skin with a gentle breeze"
                ),
                List.of(
                        "A small bird nest hidden in the branches above",
                        "Faded initials carved into the tree trunk",
                        "A forgotten toy half-buried in the warm soil",
                        "An old photograph tucked behind the bench",
                        "Wild mushrooms growing in a shaded corner"
                ),
                List.of(
                        "A sudden wave of warmth and safety washes over you",
                        "You feel a brief pang of bittersweet nostalgia",
                        "A moment of pure childhood joy flashes through",
                        "The quiet peace of that moment returns briefly"
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
                        "visual", "Muted winter tones with drifting frost and distant blue shadows",
                        "auditory", "Soft wind and muffled snow crunches ground the moment",
                        "olfactory", "Crisp air tinged with pine needles and clean ice",
                        "tactile", "Cold air bites softly while breath fogs in front of you"
                ),
                List.of(
                        "Frost etched patterns on the window glass",
                        "A woolen scarf left on the bench",
                        "Boot prints leading away from the snowman",
                        "Icicles shimmering under a pale sky"
                ),
                List.of(
                        "A hush of stillness settles in your chest",
                        "A memory of laughter echoes softly across the snow",
                        "You feel the calm that comes with fresh snowfall"
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
                        "visual", "Moonlight paints deep blues across the courtyard",
                        "auditory", "Night insects and distant calls punctuate the quiet",
                        "olfactory", "Cool air carries a faint trace of stone and damp earth",
                        "tactile", "A calm chill settles with every breath"
                ),
                List.of(
                        "A light flickers behind the distant window",
                        "Constellations you used to trace as a child",
                        "A quiet footstep echoing on stone"
                ),
                List.of(
                        "A tranquil hush wraps around you",
                        "You feel the comfort of a familiar night"
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
                        "visual", "Rain streaks blur the street with reflective puddles",
                        "auditory", "Steady rainfall with distant rumbles sets the tempo",
                        "olfactory", "Petrichor and wet earth rise with each drop",
                        "tactile", "Cool mist brushes your face as rain taps nearby"
                ),
                List.of(
                        "A note of thunder rolling in the distance",
                        "Raindrops rippling across the puddle surface",
                        "A lone streetlight buzzing softly"
                ),
                List.of(
                        "A familiar storm brings back a quiet comfort",
                        "You recall sharing an umbrella in the rain"
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
                        "visual", "Soft blooms and pastel light fill the garden",
                        "auditory", "Birdsong and gentle wind thread through the air",
                        "olfactory", "Fresh blossoms and damp grass surround you",
                        "tactile", "A cool morning breeze moves through the trees"
                ),
                List.of(
                        "Petals caught in a small breeze",
                        "A ribbon tied to the bench arm",
                        "Fresh footprints in the soft soil"
                ),
                List.of(
                        "A hopeful warmth rises with the sunlight",
                        "You feel the excitement of beginnings"
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
                        "visual", "Golden light filters through rustling leaves",
                        "auditory", "Dry leaves crackle beneath a gentle breeze",
                        "olfactory", "A faint scent of wood smoke and crisp air",
                        "tactile", "Cool air carries the promise of colder days"
                ),
                List.of(
                        "A single leaf spiraling down from the canopy",
                        "Warm light spilling from the house window",
                        "A soft crunch underfoot"
                ),
                List.of(
                        "A wistful memory of autumn evenings returns",
                        "You feel a calm acceptance settle in"
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
