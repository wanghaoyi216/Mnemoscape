package com.mnemoscape.common.admin.codec;

import java.util.Optional;

/**
 * Strict, case-sensitive codec for the admin dashboard {@link Dimension} enum.
 *
 * <p>Parsing rules (per Requirements 17.1, 17.2):
 * <ul>
 *   <li>Only the canonical uppercase {@code name()} form is accepted
 *       ({@code "DAILY"}, {@code "WEEKLY"}, {@code "MONTHLY"}, {@code "YEARLY"}).</li>
 *   <li>Lowercase, mixed-case, aliases, and surrounding whitespace are rejected.</li>
 *   <li>{@code null} input is rejected and returns {@link Optional#empty()}.</li>
 *   <li>Parse failures return {@link Optional#empty()} rather than throwing.</li>
 * </ul>
 *
 * <p>{@link #print(Dimension)} emits the canonical {@code name()} form so that
 * {@code tryParse(print(d)) == Optional.of(d)} for every defined enum value
 * (round-trip property — Property 1).
 */
public final class TimeDimensionCodec {

    /** Time bucket dimension supported by every admin aggregation endpoint. */
    public enum Dimension {
        DAILY,
        WEEKLY,
        MONTHLY,
        YEARLY
    }

    private TimeDimensionCodec() {
        // utility class
    }

    /**
     * Strictly parse a dimension token. Only the canonical uppercase form is
     * accepted; any other input (lowercase, alias, padded with whitespace,
     * {@code null}) yields {@link Optional#empty()}.
     *
     * @param raw the raw token, exactly as received from the wire
     * @return the matching {@link Dimension}, or {@link Optional#empty()} when
     *         the input does not match a canonical name
     */
    public static Optional<Dimension> tryParse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (Dimension d : Dimension.values()) {
            if (d.name().equals(raw)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /**
     * Emit the canonical uppercase form of the dimension.
     *
     * @param dimension the dimension; must not be {@code null}
     * @return {@code dimension.name()}
     * @throws NullPointerException if {@code dimension} is {@code null}
     */
    public static String print(Dimension dimension) {
        if (dimension == null) {
            throw new NullPointerException("dimension must not be null");
        }
        return dimension.name();
    }
}
