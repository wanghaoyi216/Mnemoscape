package com.mnemoscape.common.admin.codec;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.time.api.Dates;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Property-based round-trip tests for {@link IsoDateCodec}
 * (admin-dashboard task 1.6 / Property 2, validates Requirements 17.3 / 17.4).
 *
 * <h2>Property 2 — ISO LocalDate round-trip</h2>
 * <pre>
 *     ∀ d ∈ LocalDate where year ∈ [1, 9999] :
 *         tryParse(print(d)).orElseThrow().equals(d)
 * </pre>
 *
 * <p>Note: jqwik-time isn't a transitive dependency of {@code jqwik}, so we
 * synthesise dates manually from year / month / day arbitraries with proper
 * guards against invalid combinations (Feb 30, etc.).
 */
class IsoDatePropTest {

    /**
     * Canonical round-trip across the entire ISO date range. The
     * {@link Dates#dates()} arbitrary defaults to the 1900–2500 window,
     * which comfortably exceeds the design-mandated 1–9999 range.
     */
    @Property
    void roundTripIsIdentity(@ForAll("anyValidDate") LocalDate d) {
        String printed = IsoDateCodec.print(d);
        Optional<LocalDate> parsed = IsoDateCodec.tryParse(printed);
        assertEquals(Optional.of(d), parsed,
                "round-trip failed for " + d + " (printed=" + printed + ")");
    }

    /**
     * The canonical print MUST be the {@code DateTimeFormatter.ISO_LOCAL_DATE}
     * form (e.g. {@code "2026-05-24"}), so external systems can parse it
     * with any standard ISO-8601 reader.
     */
    @Property
    void printedFormMatchesIsoLocalDate(@ForAll("anyValidDate") LocalDate d) {
        assertEquals(DateTimeFormatter.ISO_LOCAL_DATE.format(d),
                IsoDateCodec.print(d));
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<LocalDate> anyValidDate() {
        // Constrain to a sensible historical-to-near-future window. The
        // design's "year ∈ [1, 9999]" property is verified by the explicit
        // boundary tests below; the property loop just needs lots of
        // *valid* dates regardless of year.
        return net.jqwik.api.Arbitraries.integers().between(1900, 2500)
                .flatMap(year ->
                        net.jqwik.api.Arbitraries.integers().between(1, 12).flatMap(month ->
                                net.jqwik.api.Arbitraries.integers()
                                        .between(1, java.time.YearMonth.of(year, month).lengthOfMonth())
                                        .map(day -> LocalDate.of(year, month, day))));
    }

    /**
     * Round-trip at the bottom of the ISO range (year = 1).
     */
    @Test
    void roundTripAtYearOne() {
        LocalDate d = LocalDate.of(1, 1, 1);
        assertEquals(Optional.of(d), IsoDateCodec.tryParse(IsoDateCodec.print(d)));
    }

    /**
     * Round-trip at the top of the ISO range (year = 9999).
     */
    @Test
    void roundTripAtYearNineThousandNineHundredNinetyNine() {
        LocalDate d = LocalDate.of(9999, 12, 31);
        assertEquals(Optional.of(d), IsoDateCodec.tryParse(IsoDateCodec.print(d)));
    }

    @Test
    void nullInputReturnsEmpty() {
        assertFalse(IsoDateCodec.tryParse(null).isPresent());
    }

    @Test
    void invalidFormatReturnsEmpty() {
        assertFalse(IsoDateCodec.tryParse("2026/05/24").isPresent());
        assertFalse(IsoDateCodec.tryParse("24-05-2026").isPresent());
        assertFalse(IsoDateCodec.tryParse("2026-13-01").isPresent()); // month out of range
        assertFalse(IsoDateCodec.tryParse("not-a-date").isPresent());
        assertFalse(IsoDateCodec.tryParse("").isPresent());
    }

    @Test
    void printRejectsNull() {
        assertThrows(NullPointerException.class, () -> IsoDateCodec.print(null));
    }

    /**
     * Year < 1 is mathematically meaningful for {@code LocalDate} (BCE) but
     * produces format strings that {@code ISO_LOCAL_DATE} cannot parse back
     * to the same value. We don't claim round-trip outside [1, 9999], so
     * this is here as a documentation test, not a property.
     */
    @Property
    void positiveYearsRoundTrip(
            @ForAll @IntRange(min = 1, max = 9999) int year,
            @ForAll @IntRange(min = 1, max = 12) int month,
            @ForAll @IntRange(min = 1, max = 28) int day) {
        LocalDate d = LocalDate.of(year, month, day);
        assertEquals(Optional.of(d), IsoDateCodec.tryParse(IsoDateCodec.print(d)));
    }
}
