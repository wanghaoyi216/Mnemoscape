package com.mnemoscape.gateway.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link HealthHandler#computeOverall(Map)}
 * (admin-dashboard task 3.5, validates Requirement 18.4).
 *
 * <p>The aggregator's overall-status rules:
 * <pre>
 *   DOWN     iff any critical (auth/memory/redis) is DOWN
 *   DEGRADED iff any component is DEGRADED, or any non-critical is DOWN
 *   UP       otherwise
 * </pre>
 *
 * <p>We test the rule engine directly rather than the full reactive probe
 * pipeline — the WebClient + Redis I/O paths are exercised in integration.
 * The classification logic is the only place where errors in the rule
 * engine could silently produce wrong overall states, so it gets the
 * unit-test coverage.
 */
class HealthHandlerTest {

    /** Build a {@link HealthHandler.ComponentStatus} with explicit status. */
    private static HealthHandler.ComponentStatus comp(String name, HealthHandler.Status status) {
        return new HealthHandler.ComponentStatus(name, status, 10L,
                status == HealthHandler.Status.UP ? null : status.name().toLowerCase());
    }

    private static Map<String, HealthHandler.ComponentStatus> componentsAll(HealthHandler.Status status) {
        Map<String, HealthHandler.ComponentStatus> m = new LinkedHashMap<>();
        m.put(HealthHandler.AUTH, comp(HealthHandler.AUTH, status));
        m.put(HealthHandler.MEMORY, comp(HealthHandler.MEMORY, status));
        m.put(HealthHandler.RESONANCE, comp(HealthHandler.RESONANCE, status));
        m.put(HealthHandler.ASSET, comp(HealthHandler.ASSET, status));
        m.put(HealthHandler.AI, comp(HealthHandler.AI, status));
        m.put(HealthHandler.REDIS, comp(HealthHandler.REDIS, status));
        return m;
    }

    @Test
    @DisplayName("all UP → UP")
    void allUpYieldsUp() {
        assertEquals(HealthHandler.Status.UP,
                HealthHandler.computeOverall(componentsAll(HealthHandler.Status.UP)));
    }

    @Test
    @DisplayName("any critical DOWN forces DOWN")
    void authDownForcesDown() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.AUTH, comp(HealthHandler.AUTH, HealthHandler.Status.DOWN));
        assertEquals(HealthHandler.Status.DOWN, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("memory-service DOWN forces DOWN")
    void memoryDownForcesDown() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.MEMORY, comp(HealthHandler.MEMORY, HealthHandler.Status.DOWN));
        assertEquals(HealthHandler.Status.DOWN, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("redis DOWN forces DOWN")
    void redisDownForcesDown() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.REDIS, comp(HealthHandler.REDIS, HealthHandler.Status.DOWN));
        assertEquals(HealthHandler.Status.DOWN, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("non-critical DOWN (asset-service) is only DEGRADED")
    void nonCriticalDownIsDegraded() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.ASSET, comp(HealthHandler.ASSET, HealthHandler.Status.DOWN));
        assertEquals(HealthHandler.Status.DEGRADED, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("non-critical DOWN (resonance-service) is only DEGRADED")
    void resonanceDownIsDegraded() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.RESONANCE, comp(HealthHandler.RESONANCE, HealthHandler.Status.DOWN));
        assertEquals(HealthHandler.Status.DEGRADED, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("non-critical DOWN (ai-service) is only DEGRADED")
    void aiDownIsDegraded() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.AI, comp(HealthHandler.AI, HealthHandler.Status.DOWN));
        assertEquals(HealthHandler.Status.DEGRADED, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("any DEGRADED forces DEGRADED (no DOWN required)")
    void anyDegradedIsDegraded() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.MEMORY, comp(HealthHandler.MEMORY, HealthHandler.Status.DEGRADED));
        assertEquals(HealthHandler.Status.DEGRADED, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("critical DOWN trumps DEGRADED elsewhere")
    void criticalDownTrumpsOtherDegraded() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.AI, comp(HealthHandler.AI, HealthHandler.Status.DEGRADED));
        m.put(HealthHandler.AUTH, comp(HealthHandler.AUTH, HealthHandler.Status.DOWN));
        assertEquals(HealthHandler.Status.DOWN, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("multiple DEGRADED components yield DEGRADED")
    void multipleDegradedYieldsDegraded() {
        Map<String, HealthHandler.ComponentStatus> m = componentsAll(HealthHandler.Status.UP);
        m.put(HealthHandler.MEMORY, comp(HealthHandler.MEMORY, HealthHandler.Status.DEGRADED));
        m.put(HealthHandler.RESONANCE, comp(HealthHandler.RESONANCE, HealthHandler.Status.DEGRADED));
        assertEquals(HealthHandler.Status.DEGRADED, HealthHandler.computeOverall(m));
    }

    @Test
    @DisplayName("ComponentStatus.toMap omits reason when status is UP")
    void componentStatusOmitsReasonWhenUp() {
        HealthHandler.ComponentStatus c = comp("auth-service", HealthHandler.Status.UP);
        Map<String, Object> m = c.toMap();
        assertEquals("UP", m.get("status"));
        assertEquals(false, m.containsKey("reason"));
    }

    @Test
    @DisplayName("ComponentStatus.toMap includes reason when status is DEGRADED")
    void componentStatusIncludesReasonWhenDegraded() {
        HealthHandler.ComponentStatus c = comp("memory-service", HealthHandler.Status.DEGRADED);
        Map<String, Object> m = c.toMap();
        assertEquals("DEGRADED", m.get("status"));
        assertEquals(true, m.containsKey("reason"));
    }
}
