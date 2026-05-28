package com.mnemoscape.memory.admin;

import com.mnemoscape.memory.admin.dto.HeatmapPoint;
import com.mnemoscape.memory.admin.dto.HeatmapRow;
import com.mnemoscape.memory.repository.MemoryRepository;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for {@link AdminStatsService#aggregateHeatmap(String)}
 * (admin-dashboard tasks 7.14 / 7.15, validates Properties 7 / 8 and
 * Requirements 9.3 / 9.4).
 *
 * <h2>Property 7 — Heatmap intensity normalisation</h2>
 * <pre>
 *     ∀ p ∈ aggregateHeatmap(res) : 0 &lt; p.intensity ≤ 1.0
 *     ∃ p ∈ aggregateHeatmap(res) : p.intensity == 1.0
 * </pre>
 *
 * <h2>Property 8 — Heatmap grid centre alignment</h2>
 * <pre>
 *     ∀ p ∈ aggregateHeatmap(res):
 *         p.lat == k * step + step/2 for some integer k
 *         p.lon == k' * step + step/2 for some integer k'
 *     where step ∈ {5.0, 1.0, 0.25}
 * </pre>
 *
 * <p>Note: Properties 7/8 hold over the JAVA-side aggregator's output. The
 * MySQL native query does the snap-to-grid quantisation; here we replay it
 * faithfully through a mocked {@link MemoryRepository} so the test is hermetic
 * (no DB needed) yet exercises {@code AdminStatsService.aggregateHeatmap}'s
 * normalisation and ordering paths end-to-end.
 */
class HeatmapAggregatorPropTest {

    /** Map a resolution string to its declared grid step. */
    private static double stepFor(String resolution) {
        return switch (resolution) {
            case "LOW"    -> 5.0;
            case "MEDIUM" -> 1.0;
            case "HIGH"   -> 0.25;
            default       -> throw new IllegalArgumentException(resolution);
        };
    }

    /**
     * Build an AdminStatsService whose repository returns the supplied rows
     * for the heatmap query. AdminMetrics / AuthServiceClient are not used
     * by aggregateHeatmap, so we leave them un-injected.
     */
    private static AdminStatsService buildService(List<HeatmapRow> rows) {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.heatmapBuckets(anyDouble())).thenReturn(rows);
        return new AdminStatsService(repo);
    }

    /**
     * Property 7 — every emitted point has intensity in (0, 1] and at least
     * one point has intensity == 1.0 (the max-rawCount cell maps to 1).
     */
    @Property
    void allIntensitiesInRange(
            @ForAll("resolution") String resolution,
            @ForAll @Size(min = 1, max = 25) List<@net.jqwik.api.constraints.LongRange(min = 1L, max = 10_000L) Long> counts) {
        double step = stepFor(resolution);
        // Each cell must be at a unique (lat, lon) so the SQL GROUP BY result
        // doesn't have duplicates the aggregator would dedupe-via-Last.
        List<HeatmapRow> rows = buildRowsAtUniqueGridCenters(step, counts);
        AdminStatsService svc = buildService(rows);

        List<HeatmapPoint> points = svc.aggregateHeatmap(resolution);

        assertEquals(rows.size(), points.size(),
                "aggregator must emit one point per row");
        boolean sawOne = false;
        for (HeatmapPoint p : points) {
            assertTrue(p.intensity() > 0.0,
                    "intensity must be strictly positive, got " + p.intensity());
            assertTrue(p.intensity() <= 1.0,
                    "intensity must be ≤ 1.0, got " + p.intensity());
            if (p.intensity() == 1.0) {
                sawOne = true;
            }
        }
        assertTrue(sawOne,
                "max-count cell must normalise to intensity == 1.0; intensities were "
                        + points.stream().map(p -> Double.toString(p.intensity())).toList());
    }

    /**
     * Property 8 — every emitted point sits exactly on a grid cell centre
     * for the supplied step. The aggregator just forwards the SQL-quantised
     * values, so this property is really verifying that no "sanitisation"
     * mid-flight breaks the alignment contract documented to consumers.
     */
    @Property
    void allPointsAlignToGridCenters(
            @ForAll("resolution") String resolution,
            @ForAll @Size(min = 1, max = 25) List<@net.jqwik.api.constraints.LongRange(min = 1L, max = 10_000L) Long> counts) {
        double step = stepFor(resolution);
        List<HeatmapRow> rows = buildRowsAtUniqueGridCenters(step, counts);
        AdminStatsService svc = buildService(rows);

        List<HeatmapPoint> points = svc.aggregateHeatmap(resolution);

        for (HeatmapPoint p : points) {
            assertOnGridCenter(p.lat(), step, "lat");
            assertOnGridCenter(p.lon(), step, "lon");
        }
    }

    /**
     * Empty SQL result must produce empty output (the aggregator's documented
     * fast-path branch). This is the design's "boundary scenario" §"没有 PUBLIC 记忆".
     */
    @Property
    void emptyRowsProducesEmptyOutput(@ForAll("resolution") String resolution) {
        AdminStatsService svc = buildService(List.of());
        List<HeatmapPoint> points = svc.aggregateHeatmap(resolution);
        assertEquals(0, points.size());
    }

    /**
     * Output must be stable-sorted by (lat, lon) ascending for byte-stable
     * cache hits across requests.
     */
    @Property
    void outputIsSortedByLatThenLon(
            @ForAll("resolution") String resolution,
            @ForAll @Size(min = 2, max = 25) List<@net.jqwik.api.constraints.LongRange(min = 1L, max = 10_000L) Long> counts) {
        double step = stepFor(resolution);
        List<HeatmapRow> rows = buildRowsAtUniqueGridCenters(step, counts);
        AdminStatsService svc = buildService(rows);

        List<HeatmapPoint> points = svc.aggregateHeatmap(resolution);

        for (int i = 1; i < points.size(); i++) {
            HeatmapPoint a = points.get(i - 1);
            HeatmapPoint b = points.get(i);
            int cmp = Double.compare(a.lat(), b.lat());
            if (cmp == 0) cmp = Double.compare(a.lon(), b.lon());
            assertTrue(cmp <= 0,
                    "expected non-descending order, got " + a + " then " + b);
        }
    }

    // --- Helpers -----------------------------------------------------------

    /** Build a row at a uniquely-located grid centre seeded by `i`. */
    private static List<HeatmapRow> buildRowsAtUniqueGridCenters(double step, List<Long> counts) {
        List<HeatmapRow> rows = new ArrayList<>(counts.size());
        for (int i = 0; i < counts.size(); i++) {
            // Tile the cells along the equator with cell-stride spacing so they
            // never collide and stay inside [-180, 180] for our small N.
            int kLat = (i / 36) - 5; // -5..(N-1)/36-5
            int kLon = (i % 36) - 18;
            double lat = round6(kLat * step + step / 2.0);
            double lon = round6(kLon * step + step / 2.0);
            long c = counts.get(i);
            rows.add(stubRow(lat, lon, c));
        }
        return rows;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    private static HeatmapRow stubRow(double lat, double lon, long count) {
        return new HeatmapRow() {
            @Override public double getLatBucket() { return lat; }
            @Override public double getLngBucket() { return lon; }
            @Override public long getRawCount() { return count; }
        };
    }

    /**
     * Assert {@code coord} lies on a grid centre {@code k * step + step/2} for
     * some integer {@code k}, allowing for 1e-6 floating-point slack
     * (matches the SQL ROUND(..., 6)).
     */
    private static void assertOnGridCenter(double coord, double step, String axis) {
        double offsetFromCenter = coord - step / 2.0;
        double k = offsetFromCenter / step;
        double rounded = Math.round(k);
        double residual = Math.abs(k - rounded);
        assertTrue(residual < 1e-4,
                axis + "=" + coord + " is NOT on a grid centre for step=" + step
                        + " (k=" + k + ", residual=" + residual + ")");
    }

    @Provide
    Arbitrary<String> resolution() {
        return Arbitraries.of("LOW", "MEDIUM", "HIGH");
    }
}
