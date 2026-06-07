package com.mnemoscape.common.admin.codec;

import com.mnemoscape.common.admin.codec.TimeDimensionCodec.Dimension;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-based round-trip tests for {@link TimeDimensionCodec}
 * (admin-dashboard task 1.4 / Property 1, validates Requirements 17.1 / 17.2).
 *
 * <h2>Property 1 — TimeDimension round-trip</h2>
 * <pre>
 *     ∀ d ∈ Dimension.values() :
 *         tryParse(print(d)).orElseThrow() == d
 * </pre>
 *
 * <p>The enum has only four values so jqwik's exhaustive sampling exercises
 * every member on every run. The property is reinforced by negative-input
 * tests asserting that lowercase / aliases / null are rejected (R17.1).
 */
class TimeDimensionPropTest {

    /**
     * Exhaustive round-trip — for each enum value, formatting and parsing
     * must yield the original value.
     */
    @Property
    void roundTripIsIdentity(@ForAll Dimension d) {
        String printed = TimeDimensionCodec.print(d);
        Optional<Dimension> parsed = TimeDimensionCodec.tryParse(printed);
        assertTrue(parsed.isPresent(),
                "round-trip parse failed for canonical print " + printed);
        assertEquals(d, parsed.get(),
                "round-trip lost identity: " + d + " → " + printed
                        + " → " + parsed.get());
    }

    /**
     * The canonical print is exactly the enum's {@code name()} form — no
     * whitespace, no decoration, no localisation. Specifying this explicitly
     * guards against accidental refactors that introduce padding.
     */
    @Property
    void printedFormIsCanonicalName(@ForAll Dimension d) {
        assertEquals(d.name(), TimeDimensionCodec.print(d));
    }

    /**
     * Lower-case / mixed-case / alias inputs MUST be rejected (R17.1 strict
     * case-sensitive parsing).
     */
    @Property
    void rejectsLowercaseVariants(@ForAll Dimension d) {
        String lower = d.name().toLowerCase(Locale.ROOT);
        assertFalse(TimeDimensionCodec.tryParse(lower).isPresent(),
                "lowercase variant must be rejected: " + lower);
    }

    /**
     * Whitespace-padded canonical input MUST be rejected (R17.1 forbids
     * surrounding whitespace).
     */
    @Property
    void rejectsWhitespacePadded(@ForAll Dimension d, @ForAll @WhitespaceProvider String pad) {
        String padded = pad + d.name() + pad;
        assertFalse(TimeDimensionCodec.tryParse(padded).isPresent(),
                "whitespace-padded variant must be rejected: '" + padded + "'");
    }

    /** Provider for non-empty whitespace strings (1–3 spaces). */
    @Provide
    Arbitrary<String> whitespaces() {
        return Arbitraries.strings()
                .withChars(' ', '\t')
                .ofMinLength(1)
                .ofMaxLength(3);
    }

    /** Marker annotation to wire {@link #whitespaces()} provider into a property. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER)
    @net.jqwik.api.From("whitespaces")
    @interface WhitespaceProvider {}

    @Test
    void nullInputReturnsEmpty() {
        assertFalse(TimeDimensionCodec.tryParse(null).isPresent());
    }

    @Test
    void emptyStringReturnsEmpty() {
        assertFalse(TimeDimensionCodec.tryParse("").isPresent());
    }

    @Test
    void nonsenseStringReturnsEmpty() {
        assertFalse(TimeDimensionCodec.tryParse("HOURLY").isPresent());
        assertFalse(TimeDimensionCodec.tryParse("d-a-i-l-y").isPresent());
    }
}
