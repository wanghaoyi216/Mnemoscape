package com.mnemoscape.common.admin.codec;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Codec for ISO 8601 calendar dates ({@code YYYY-MM-DD}) used by every admin
 * aggregation endpoint that accepts a {@code from} / {@code to} query
 * parameter.
 *
 * <p>This is a thin wrapper over {@link DateTimeFormatter#ISO_LOCAL_DATE}:
 * <ul>
 *   <li>{@link #print(LocalDate)} emits the canonical {@code YYYY-MM-DD}
 *       form (Requirement 17.3).</li>
 *   <li>{@link #tryParse(String)} returns {@link Optional#empty()} for
 *       {@code null} input or any string that {@code ISO_LOCAL_DATE} cannot
 *       parse, instead of letting {@link DateTimeParseException} escape.
 *       Callers translate the empty case into HTTP 400 {@code INVALID_RANGE}
 *       (Requirement 17.3).</li>
 * </ul>
 *
 * <p>The pair satisfies the round-trip property (Property 2 — Requirement
 * 17.4):
 * <pre>
 *   &#x2200; d &#x2208; LocalDate (year &#x2208; [1, 9999]):
 *       tryParse(print(d)).orElseThrow().equals(d)
 * </pre>
 */
public final class IsoDateCodec {

    private IsoDateCodec() {
        // utility class
    }

    /**
     * Emit the canonical ISO 8601 calendar form ({@code YYYY-MM-DD}).
     *
     * @param date the date; must not be {@code null}
     * @return the ISO 8601 representation, e.g. {@code "2025-01-31"}
     * @throws NullPointerException if {@code date} is {@code null}
     */
    public static String print(LocalDate date) {
        if (date == null) {
            throw new NullPointerException("date must not be null");
        }
        return DateTimeFormatter.ISO_LOCAL_DATE.format(date);
    }

    /**
     * Attempt to parse an ISO 8601 calendar date.
     *
     * <p>Returns {@link Optional#empty()} for {@code null} input or any
     * string that does not match {@link DateTimeFormatter#ISO_LOCAL_DATE};
     * never throws {@link DateTimeParseException}.
     *
     * @param raw the raw token, exactly as received from the wire
     * @return the parsed {@link LocalDate}, or {@link Optional#empty()} when
     *         the input cannot be parsed
     */
    public static Optional<LocalDate> tryParse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
