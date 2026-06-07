package com.mnemoscape.memory.admin.util;

import com.mnemoscape.common.admin.codec.TimeDimensionCodec.Dimension;
import com.mnemoscape.memory.admin.util.TimeBucketing.Bucket;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-based tests for {@link TimeBucketing} (admin-dashboard tasks
 * 7.3 / 7.4 / 7.5 / 7.6, validates Properties 4 / 5 / 6 / 10 and
 * Requirements 6.1 / 6.5 / 7.1).
 *
 * <p>Maps to the design's correctness properties:
 * <ul>
 *   <li><b>Property 4</b> — bucket-series length equals bucket count.</li>
 *   <li><b>Property 5</b> — buckets are strictly ascending (lex order matches
 *       chronological order).</li>
 *   <li><b>Property 6</b> — zero-fill produces non-negative counts.</li>
 *   <li><b>Property 10</b> — {@code defaultFrom} matches the dimension's
 *       canonical window.</li>
 * </ul>
 */
class TimeBucketingPropTest {

    /**
     * Property 4 — for every (dim, from, to) inside the legal range, the
     * series length equals the number of buckets in that range.
     */
    @Property
    void seriesLengthEqualsBucketCount(
            @ForAll Dimension dim,
            @ForAll("legalDateRange") DateRange range) {
        List<LocalDateTime> series = TimeBucketing.bucketSeries(dim, range.from(), range.to());
        long expected = expectedBucketCount(dim, range.from(), range.to());
        assertEquals(expected, series.size(),
                "series length mismatch for " + dim + " " + range);
    }

    /**
     * Property 5 — the bucket series is strictly ascending in time AND its
     * canonical key form is lexicographically ascending. The lex match is what
     * makes JSON-serialized series stable across clients.
     */
    @Property
    void seriesIsStrictlyAscending(
            @ForAll Dimension dim,
            @ForAll("legalDateRange") DateRange range) {
        List<LocalDateTime> series = TimeBucketing.bucketSeries(dim, range.from(), range.to());
        for (int i = 1; i < series.size(); i++) {
            assertTrue(series.get(i).isAfter(series.get(i - 1)),
                    "expected strict time ascent at index " + i + " for " + dim
                            + ", got " + series.get(i - 1) + " then " + series.get(i));
            String prev = TimeBucketing.formatBucket(dim, series.get(i - 1));
            String curr = TimeBucketing.formatBucket(dim, series.get(i));
            assertTrue(curr.compareTo(prev) > 0,
                    "expected lexicographic ascent: '" + prev + "' < '" + curr + "'");
        }
    }

    /**
     * Property 6 — zero-fill always produces non-negative counts. Negative
     * raw counts produce IllegalArgumentException (defensive), so the
     * non-negative invariant on the output is true vacuously when raw counts
     * are non-negative; the property iterates over arbitrary non-negative
     * count maps and verifies the invariant holds.
     */
    @Property
    void zeroFillProducesNonNegativeCounts(
            @ForAll Dimension dim,
            @ForAll("smallDateRange") DateRange range,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 1_000_000) long count) {
        // Pick the first bucket in range and seed it with `count`.
        LocalDateTime firstStart = TimeBucketing.bucketSeries(dim, range.from(), range.to()).get(0);
        String key = TimeBucketing.formatBucket(dim, firstStart);
        Map<String, Long> raw = new HashMap<>();
        raw.put(key, count);

        List<Bucket> filled = TimeBucketing.zeroFillBuckets(dim, range.from(), range.to(), raw);
        for (Bucket b : filled) {
            assertTrue(b.count() >= 0L,
                    "non-negative invariant violated at " + b.bucket() + " (count=" + b.count() + ")");
        }
    }

    /**
     * Property 10 — {@code defaultFrom} maps each dimension to its canonical
     * lookback (DAILY: 30 days, WEEKLY: 12 weeks, MONTHLY: 12 months, YEARLY:
     * 5 years). The property iterates over arbitrary `to` dates so the
     * arithmetic is exercised at month-end / leap-year edges.
     */
    @Property
    void defaultFromMatchesCanonicalWindow(@ForAll("legalDate") LocalDate to) {
        assertEquals(to.minusDays(30),   TimeBucketing.defaultFrom(Dimension.DAILY, to));
        assertEquals(to.minusWeeks(12),  TimeBucketing.defaultFrom(Dimension.WEEKLY, to));
        assertEquals(to.minusMonths(12), TimeBucketing.defaultFrom(Dimension.MONTHLY, to));
        assertEquals(to.minusYears(5),   TimeBucketing.defaultFrom(Dimension.YEARLY, to));
    }

    // ------------------------------- Helpers -------------------------------

    /** Compute the expected bucket count using bucket-aligned starts. */
    private static long expectedBucketCount(Dimension dim, LocalDate from, LocalDate to) {
        LocalDate fromStart = TimeBucketing.bucketStart(dim, from.atStartOfDay()).toLocalDate();
        LocalDate toStart = TimeBucketing.bucketStart(dim, to.atStartOfDay()).toLocalDate();
        return switch (dim) {
            case DAILY   -> ChronoUnit.DAYS.between(fromStart, toStart) + 1L;
            case WEEKLY  -> ChronoUnit.WEEKS.between(fromStart, toStart) + 1L;
            case MONTHLY -> ChronoUnit.MONTHS.between(fromStart, toStart) + 1L;
            case YEARLY  -> ChronoUnit.YEARS.between(fromStart, toStart) + 1L;
        };
    }

    /** Date range where bucket count <= 366 for any dimension. */
    @Provide
    Arbitrary<DateRange> legalDateRange() {
        return legalDate().flatMap(from ->
                Arbitraries.integers().between(0, 365).map(offsetDays ->
                        new DateRange(from, from.plusDays(offsetDays))));
    }

    /** Smaller window for the per-bucket count property (cheaper exhaustion). */
    @Provide
    Arbitrary<DateRange> smallDateRange() {
        return legalDate().flatMap(from ->
                Arbitraries.integers().between(0, 30).map(offsetDays ->
                        new DateRange(from, from.plusDays(offsetDays))));
    }

    @Provide
    Arbitrary<LocalDate> legalDate() {
        // Bound below so leap-year / month-rollover paths get exercised but the
        // backend never sees prehistoric dates.
        return Arbitraries.integers().between(2000, 2099).flatMap(year ->
                Arbitraries.integers().between(1, 12).flatMap(month ->
                        Arbitraries.integers()
                                .between(1, java.time.YearMonth.of(year, month).lengthOfMonth())
                                .map(day -> LocalDate.of(year, month, day))));
    }

    /** Inclusive [from, to] range carrier used as a property parameter. */
    record DateRange(LocalDate from, LocalDate to) {}
}
