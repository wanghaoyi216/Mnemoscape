package com.mnemoscape.memory.admin.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property-based round-trip test for {@link HeatmapPoint} JSON serialization
 * (admin-dashboard task 7.16 / Property 3, validates Requirements 17.5).
 *
 * <h2>Property 3 — Heatmap JSON round-trip</h2>
 * <pre>
 *     ∀ ps ∈ List&lt;HeatmapPoint&gt; (every component is finite double) :
 *         mapper.readValue(mapper.writeValueAsString(ps),
 *                          new TypeReference&lt;List&lt;HeatmapPoint&gt;&gt;() {})
 *             .equals(ps)
 * </pre>
 *
 * <p>The arbitrary generators are deliberately constrained to finite doubles —
 * Jackson's default configuration rejects {@code NaN} / {@code ±Infinity} on
 * the write path, and the heatmap aggregator is documented to never produce
 * those values (see {@code HeatmapPoint} javadoc and the design doc's
 * §Round-trip Properties).
 */
class HeatmapJsonPropTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<HeatmapPoint>> LIST_TYPE = new TypeReference<>() {};

    @Property
    void roundTripIsIdentity(@ForAll("heatmapPointLists") List<HeatmapPoint> points) throws Exception {
        String json = MAPPER.writeValueAsString(points);
        List<HeatmapPoint> parsed = MAPPER.readValue(json, LIST_TYPE);
        assertEquals(points, parsed,
                "JSON round-trip lost identity. wrote=" + json + " parsed=" + parsed);
    }

    @Property
    void roundTripPreservesCoordinatesExactly(@ForAll("heatmapPoint") HeatmapPoint p) throws Exception {
        String json = MAPPER.writeValueAsString(p);
        HeatmapPoint parsed = MAPPER.readValue(json, HeatmapPoint.class);
        assertEquals(p.lat(), parsed.lat(), 0.0,
                "lat mismatch after round-trip");
        assertEquals(p.lon(), parsed.lon(), 0.0,
                "lon mismatch after round-trip");
        assertEquals(p.intensity(), parsed.intensity(), 0.0,
                "intensity mismatch after round-trip");
    }

    @Provide
    Arbitrary<List<HeatmapPoint>> heatmapPointLists() {
        return heatmapPoint().list().ofMinSize(0).ofMaxSize(40);
    }

    /**
     * Generate a heatmap point with sensible bounds:
     *   lat ∈ [-90, 90], lon ∈ [-180, 180], intensity ∈ (0, 1].
     * The grid algorithm guarantees these bounds at runtime; emitting only
     * in-bounds values keeps the property focused on JSON shape rather than
     * domain validation.
     */
    @Provide
    Arbitrary<HeatmapPoint> heatmapPoint() {
        Arbitrary<Double> lat = Arbitraries.doubles().between(-90.0, 90.0)
                .ofScale(6); // match the 6-decimal SQL ROUND
        Arbitrary<Double> lon = Arbitraries.doubles().between(-180.0, 180.0)
                .ofScale(6);
        Arbitrary<Double> intensity = Arbitraries.doubles().between(0.000001, 1.0)
                .ofScale(6);
        return lat.flatMap(la -> lon.flatMap(lo ->
                intensity.map(i -> new HeatmapPoint(la, lo, i))));
    }
}
