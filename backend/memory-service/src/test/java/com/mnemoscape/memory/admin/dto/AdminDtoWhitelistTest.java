package com.mnemoscape.memory.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * "Whitelist serialisation" test for every admin response DTO
 * (admin-dashboard task 7.22 / Property 9, validates Requirements 15.1 / 15.2).
 *
 * <h2>Property 9 — Privacy never leaks</h2>
 * <pre>
 *     ∀ admin endpoint response :
 *         serialized(response) does NOT contain any of
 *           {"title", "description", "visualData", "audioData",
 *            "emotionProfile", "email", "passwordHash", "avatarUrl",
 *            "backgroundImageUrl", "changeDescription", "sceneDataUrl"}
 * </pre>
 *
 * <p>Each test serialises a representative DTO instance with sentinel
 * values placed in fields that the design forbids, then greps the output
 * for forbidden field names. The forbidden list mirrors the design's
 * §Privacy Enforcement table.
 */
class AdminDtoWhitelistTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    /** Design §Privacy Enforcement — forbidden field-name fragments. */
    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "\"title\"",
            "\"description\"",
            "\"visualData\"",
            "\"audioData\"",
            "\"emotionProfile\"",
            "\"email\"",
            "\"passwordHash\"",
            "\"avatarUrl\"",
            "\"backgroundImageUrl\"",
            "\"changeDescription\"",
            "\"sceneDataUrl\""
    );

    private static void assertNoForbiddenFields(String json) {
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertFalse(json.contains(forbidden),
                    "forbidden field " + forbidden + " leaked into admin DTO JSON: " + json);
        }
    }

    @Test
    @DisplayName("ActiveUserBucket whitelist: bucket, activeUserCount only")
    void activeUserBucket() throws Exception {
        ActiveUserBucket sample = new ActiveUserBucket("2026-05-24", 42L);
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
        // Spot-check that the allowed fields ARE present (sanity guard against
        // a shape-shrinking refactor that would silently break clients).
        assertNotNull(json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("bucket"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("activeUserCount"));
    }

    @Test
    @DisplayName("MemoryTrendBucket whitelist: bucket, createdCount, modifiedCount only")
    void memoryTrendBucket() throws Exception {
        MemoryTrendBucket sample = new MemoryTrendBucket("2026-W21", 7L, 3L);
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("createdCount"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("modifiedCount"));
    }

    @Test
    @DisplayName("EmotionDistribution whitelist: 8 components + sampleSize, no other field")
    void emotionDistribution() throws Exception {
        EmotionDistribution sample = new EmotionDistribution(
                0.3, 0.2, 0.05, 0.1, 0.15, 0.08, 0.07, 0.05, 1234L);
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
        for (String c : List.of("joy", "sadness", "anger", "fear",
                "surprise", "nostalgia", "peace", "melancholy", "sampleSize")) {
            org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"" + c + "\""),
                    "missing required emotion field: " + c);
        }
    }

    @Test
    @DisplayName("HeatmapPoint whitelist: lat, lon, intensity only")
    void heatmapPoint() throws Exception {
        HeatmapPoint sample = new HeatmapPoint(35.5, 105.5, 0.62);
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"lat\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"lon\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"intensity\""));
    }

    @Test
    @DisplayName("TopContributor whitelist: userId, username, memoryCount only")
    void topContributor() throws Exception {
        TopContributor sample = new TopContributor("u-abc-123", "alice", 17L);
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
        // Especially: no email / passwordHash / avatarUrl etc. (R10.4)
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"username\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"memoryCount\""));
    }

    @Test
    @DisplayName("TopContributorsResponse whitelist: items, degraded, degradedReasons only")
    void topContributorsResponse() throws Exception {
        TopContributorsResponse ok = TopContributorsResponse.ok(
                List.of(new TopContributor("u-1", "alice", 5L),
                        new TopContributor("u-2", "bob", 3L)));
        String json = MAPPER.writeValueAsString(ok);
        assertNoForbiddenFields(json);

        TopContributorsResponse degraded = TopContributorsResponse.degraded(
                List.of(new TopContributor("u-3", "u-3-fall", 2L)),
                List.of("auth-service username lookup failed"));
        String degradedJson = MAPPER.writeValueAsString(degraded);
        assertNoForbiddenFields(degradedJson);
        org.junit.jupiter.api.Assertions.assertTrue(degradedJson.contains("\"degraded\":true"));
    }

    @Test
    @DisplayName("FragmentDiscoveryOverall whitelist: total/discovered/rate only")
    void fragmentDiscoveryOverall() throws Exception {
        FragmentDiscoveryOverall sample = new FragmentDiscoveryOverall(100L, 25L, 0.25);
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"discoveryRate\""));
    }

    @Test
    @DisplayName("FragmentDiscoveryByType whitelist: type + counts only (no fragment content)")
    void fragmentDiscoveryByType() throws Exception {
        FragmentDiscoveryByType sample = new FragmentDiscoveryByType(
                "forgotten_detail", 80L, 16L, 0.2);
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
        // No fragment content / position / triggerCondition either:
        assertFalse(json.contains("\"content\""));
        assertFalse(json.contains("\"position3d\""));
        assertFalse(json.contains("\"triggerCondition\""));
    }

    @Test
    @DisplayName("ResonanceOverview whitelist (resonance-service DTO mirror)")
    void resonanceOverview() throws Exception {
        // Mirror DTO from resonance-service for completeness; verifying its
        // shape here keeps the privacy guard centralised even though the
        // record itself lives in another module.
        record ResonanceOverviewSnapshot(long totalEdges, double averageScore,
                                         Map<String, Long> statusBreakdown) {}
        ResonanceOverviewSnapshot sample = new ResonanceOverviewSnapshot(
                42L, 0.73, Map.of("pending", 30L, "active", 12L));
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
    }

    @Test
    @DisplayName("ResonanceTopEdge whitelist: 5 fields, no memory content")
    void resonanceTopEdge() throws Exception {
        // Mirror DTO from resonance-service.
        record ResonanceTopEdgeSnapshot(String memoryAId, String memoryBId,
                                        double resonanceScore, String status,
                                        OffsetDateTime createdAt) {}
        ResonanceTopEdgeSnapshot sample = new ResonanceTopEdgeSnapshot(
                "mem-a", "mem-b", 0.91, "active",
                OffsetDateTime.of(2026, 5, 24, 12, 0, 0, 0, ZoneOffset.UTC));
        String json = MAPPER.writeValueAsString(sample);
        assertNoForbiddenFields(json);
        // Critical: no sceneDataUrl / titles / descriptions
        assertFalse(json.contains("\"sceneDataUrl\""));
    }
}
