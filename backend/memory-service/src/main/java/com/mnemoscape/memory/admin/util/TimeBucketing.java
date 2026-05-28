package com.mnemoscape.memory.admin.util;

import com.mnemoscape.common.admin.codec.TimeDimensionCodec.Dimension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time bucketing primitives shared by every admin aggregation endpoint that
 * groups rows by a {@link Dimension} (active users, memory trends, …).
 *
 * <p>The bucket alignment is fixed and deterministic (admin-dashboard design
 * §Time Bucketing Algorithm, Requirement 6.6):
 *
 * <table>
 *   <caption>Bucket alignment per dimension</caption>
 *   <tr><th>Dimension</th><th>Bucket start</th><th>Step</th>
 *       <th>Default window</th><th>Bucket key</th></tr>
 *   <tr><td>{@code DAILY}</td>   <td>UTC 00:00 of the same calendar day</td>
 *       <td>1 day</td>   <td>30 days</td>   <td>{@code YYYY-MM-DD}</td></tr>
 *   <tr><td>{@code WEEKLY}</td>  <td>ISO week-based Monday 00:00 UTC</td>
 *       <td>7 days</td>  <td>12 weeks</td>  <td>{@code YYYY-Www}</td></tr>
 *   <tr><td>{@code MONTHLY}</td> <td>1st of month 00:00 UTC</td>
 *       <td>1 month</td> <td>12 months</td> <td>{@code YYYY-MM}</td></tr>
 *   <tr><td>{@code YEARLY}</td>  <td>1st of January 00:00 UTC</td>
 *       <td>1 year</td>  <td>5 years</td>   <td>{@code YYYY}</td></tr>
 * </table>
 *
 * <p>All inputs are interpreted as UTC; the caller is expected to pass
 * {@link LocalDateTime} values that already represent a UTC instant (the
 * memory-service stores timestamps as UTC).
 *
 * <p>Total bucket count over a request range is capped at
 * {@value #MAX_BUCKET_COUNT} (Requirement 6.4); exceeding the cap yields an
 * {@link IllegalArgumentException} which controllers map to HTTP 400
 * {@code INVALID_RANGE}.
 *
 * <p>This class is a pure utility — no Spring, no I/O, no mutable state.
 */
public final class TimeBucketing {

    /** Hard upper bound on bucket count per request (Requirement 6.4). */
    public static final long MAX_BUCKET_COUNT = 366L;

    /**
     * Generic bucket envelope returned by {@link #zeroFillBuckets}; specific
     * endpoints (active-users, memory-trends, …) map this into their own
     * white-listed response records.
     */
    public record Bucket(String bucket, long count) { }

    private static final DateTimeFormatter DAILY_FMT = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter MONTHLY_FMT = DateTimeFormatter.ofPattern("uuuu-MM");
    /** Strict {@code YYYY-Www} — 4+ digit week-based year, 2-digit ISO week. */
    private static final Pattern WEEKLY_KEY = Pattern.compile("^(-?\\d{4,})-W(\\d{2})$");
    /** Strict {@code YYYY} — 4+ digits, optional leading minus. */
    private static final Pattern YEARLY_KEY = Pattern.compile("^(-?\\d{4,})$");

    private TimeBucketing() {
        // utility class
    }

    // --- Alignment ----------------------------------------------------------

    /**
     * Snap {@code utc} down to the start of its bucket for {@code dim}.
     *
     * @param dim  the bucket dimension; must not be {@code null}
     * @param utc  the timestamp interpreted as UTC; must not be {@code null}
     * @return     the bucket start (UTC midnight on the appropriate day)
     */
    public static LocalDateTime bucketStart(Dimension dim, LocalDateTime utc) {
        Objects.requireNonNull(dim, "dim must not be null");
        Objects.requireNonNull(utc, "utc must not be null");
        LocalDate d = utc.toLocalDate();
        return switch (dim) {
            case DAILY   -> d.atStartOfDay();
            case WEEKLY  -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case MONTHLY -> d.withDayOfMonth(1).atStartOfDay();
            case YEARLY  -> d.withDayOfYear(1).atStartOfDay();
        };
    }

    /**
     * Advance one bucket step from a previously aligned bucket start.
     *
     * @param dim          the bucket dimension; must not be {@code null}
     * @param bucketStart  a bucket start (already aligned via {@link #bucketStart});
     *                     must not be {@code null}
     * @return             the next bucket start
     */
    public static LocalDateTime nextBucketStart(Dimension dim, LocalDateTime bucketStart) {
        Objects.requireNonNull(dim, "dim must not be null");
        Objects.requireNonNull(bucketStart, "bucketStart must not be null");
        return switch (dim) {
            case DAILY   -> bucketStart.plusDays(1);
            case WEEKLY  -> bucketStart.plusWeeks(1);
            case MONTHLY -> bucketStart.plusMonths(1);
            case YEARLY  -> bucketStart.plusYears(1);
        };
    }

    // --- Key codec ----------------------------------------------------------

    /**
     * Format a bucket start as the canonical key surfaced to the client.
     *
     * <p>Lexicographic order over the produced keys matches chronological
     * order within a bucket series (Property 5).
     *
     * @param dim          the bucket dimension; must not be {@code null}
     * @param bucketStart  a bucket start (already aligned); must not be {@code null}
     * @return             the canonical bucket key
     */
    public static String formatBucket(Dimension dim, LocalDateTime bucketStart) {
        Objects.requireNonNull(dim, "dim must not be null");
        Objects.requireNonNull(bucketStart, "bucketStart must not be null");
        LocalDate d = bucketStart.toLocalDate();
        return switch (dim) {
            case DAILY -> DAILY_FMT.format(d);
            case WEEKLY -> {
                int weekBasedYear = d.get(IsoFields.WEEK_BASED_YEAR);
                int week = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                yield String.format("%04d-W%02d", weekBasedYear, week);
            }
            case MONTHLY -> MONTHLY_FMT.format(d);
            case YEARLY  -> String.format("%04d", d.getYear());
        };
    }

    /**
     * Parse a bucket key back to its start instant. Inverse of
     * {@link #formatBucket} for any key produced by this class.
     *
     * @param dim        the bucket dimension; must not be {@code null}
     * @param bucketKey  the canonical key; must not be {@code null}
     * @return           the corresponding bucket start
     * @throws IllegalArgumentException if the key does not match the
     *         dimension's canonical format
     */
    public static LocalDateTime parseBucket(Dimension dim, String bucketKey) {
        Objects.requireNonNull(dim, "dim must not be null");
        Objects.requireNonNull(bucketKey, "bucketKey must not be null");
        try {
            return switch (dim) {
                case DAILY -> LocalDate.parse(bucketKey, DAILY_FMT).atStartOfDay();
                case WEEKLY -> {
                    Matcher m = WEEKLY_KEY.matcher(bucketKey);
                    if (!m.matches()) {
                        throw new IllegalArgumentException(
                                "invalid WEEKLY bucket key: " + bucketKey);
                    }
                    int weekBasedYear = Integer.parseInt(m.group(1));
                    int week = Integer.parseInt(m.group(2));
                    // Jan 4 is always in ISO week 1 of its week-based year — anchor from there.
                    LocalDate jan4 = LocalDate.of(weekBasedYear, 1, 4);
                    LocalDate weekOneMonday = jan4.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    yield weekOneMonday.plusWeeks(week - 1L).atStartOfDay();
                }
                case MONTHLY -> YearMonth.parse(bucketKey, MONTHLY_FMT).atDay(1).atStartOfDay();
                case YEARLY -> {
                    if (!YEARLY_KEY.matcher(bucketKey).matches()) {
                        throw new IllegalArgumentException(
                                "invalid YEARLY bucket key: " + bucketKey);
                    }
                    int year = Integer.parseInt(bucketKey);
                    yield LocalDate.of(year, 1, 1).atStartOfDay();
                }
            };
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "invalid " + dim + " bucket key: " + bucketKey, e);
        }
    }

    // --- Series & limits ----------------------------------------------------

    /**
     * Generate every bucket start in the closed range {@code [from, to]} for
     * the given dimension, ascending. Inclusive of both endpoints' aligned
     * bucket starts.
     *
     * <p>Always validates the range with
     * {@link #requireBucketCountWithinLimit} before allocating, so the
     * returned list contains at most {@value #MAX_BUCKET_COUNT} entries.
     *
     * @param dim   the bucket dimension; must not be {@code null}
     * @param from  inclusive start date (UTC); must not be {@code null}
     * @param to    inclusive end date (UTC); must not be {@code null}
     * @return      an unmodifiable list of bucket starts, ascending
     * @throws IllegalArgumentException when {@code from > to} or the range
     *         exceeds {@value #MAX_BUCKET_COUNT} buckets
     */
    public static List<LocalDateTime> bucketSeries(Dimension dim, LocalDate from, LocalDate to) {
        requireBucketCountWithinLimit(dim, from, to);
        LocalDateTime current = bucketStart(dim, from.atStartOfDay());
        LocalDateTime endStart = bucketStart(dim, to.atStartOfDay());
        List<LocalDateTime> out = new ArrayList<>();
        while (!current.isAfter(endStart)) {
            out.add(current);
            current = nextBucketStart(dim, current);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Validate that the range {@code [from, to]} produces no more than
     * {@value #MAX_BUCKET_COUNT} buckets at the given dimension.
     *
     * @throws IllegalArgumentException when {@code from} is after {@code to},
     *         or when the bucket count exceeds the cap
     */
    public static void requireBucketCountWithinLimit(Dimension dim, LocalDate from, LocalDate to) {
        Objects.requireNonNull(dim, "dim must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "from (" + from + ") must be on or before to (" + to + ")");
        }
        long count = bucketCount(dim, from, to);
        if (count > MAX_BUCKET_COUNT) {
            throw new IllegalArgumentException(
                    "bucket count " + count + " exceeds maximum " + MAX_BUCKET_COUNT
                            + " for dimension " + dim);
        }
    }

    /**
     * Default {@code from} for a request omitting both range parameters
     * (Requirement 6.5):
     *
     * <ul>
     *   <li>{@code DAILY}   → {@code to.minusDays(30)}</li>
     *   <li>{@code WEEKLY}  → {@code to.minusWeeks(12)}</li>
     *   <li>{@code MONTHLY} → {@code to.minusMonths(12)}</li>
     *   <li>{@code YEARLY}  → {@code to.minusYears(5)}</li>
     * </ul>
     */
    public static LocalDate defaultFrom(Dimension dim, LocalDate to) {
        Objects.requireNonNull(dim, "dim must not be null");
        Objects.requireNonNull(to, "to must not be null");
        return switch (dim) {
            case DAILY   -> to.minusDays(30);
            case WEEKLY  -> to.minusWeeks(12);
            case MONTHLY -> to.minusMonths(12);
            case YEARLY  -> to.minusYears(5);
        };
    }

    // --- Zero fill ----------------------------------------------------------

    /**
     * Materialise a dense {@code [from, to]} bucket series, looking up each
     * bucket key in {@code rawCounts} (defaults to 0 when missing).
     *
     * <p>Postconditions:
     * <ul>
     *   <li>Result size equals the bucket count of the range (Property 4).</li>
     *   <li>Result is strictly ascending by bucket key — lexicographic order
     *       matches chronological order (Property 5).</li>
     *   <li>Every {@code count} is non-negative (Property 6); a negative
     *       value in {@code rawCounts} surfaces as
     *       {@link IllegalArgumentException}.</li>
     * </ul>
     *
     * @param dim         the bucket dimension; must not be {@code null}
     * @param from        inclusive start date (UTC); must not be {@code null}
     * @param to          inclusive end date (UTC); must not be {@code null}
     * @param rawCounts   sparse mapping of bucket key → count; must not be
     *                    {@code null}
     * @return            an unmodifiable list of {@link Bucket} entries, one
     *                    per bucket in the range, ordered ascending
     */
    public static List<Bucket> zeroFillBuckets(
            Dimension dim,
            LocalDate from,
            LocalDate to,
            Map<String, Long> rawCounts) {
        Objects.requireNonNull(rawCounts, "rawCounts must not be null");
        List<LocalDateTime> starts = bucketSeries(dim, from, to);
        List<Bucket> series = new ArrayList<>(starts.size());
        for (LocalDateTime start : starts) {
            String key = formatBucket(dim, start);
            long raw = rawCounts.getOrDefault(key, 0L);
            if (raw < 0L) {
                throw new IllegalArgumentException(
                        "negative raw count for bucket " + key + ": " + raw);
            }
            series.add(new Bucket(key, raw));
        }
        return Collections.unmodifiableList(series);
    }

    // --- Internals ----------------------------------------------------------

    /**
     * Inclusive bucket count between two dates after aligning each to its
     * bucket start. Always {@code >= 1} when {@code from <= to}.
     */
    private static long bucketCount(Dimension dim, LocalDate from, LocalDate to) {
        LocalDate fromStart = bucketStart(dim, from.atStartOfDay()).toLocalDate();
        LocalDate toStart = bucketStart(dim, to.atStartOfDay()).toLocalDate();
        return switch (dim) {
            case DAILY   -> ChronoUnit.DAYS.between(fromStart, toStart) + 1L;
            case WEEKLY  -> ChronoUnit.WEEKS.between(fromStart, toStart) + 1L;
            case MONTHLY -> ChronoUnit.MONTHS.between(fromStart, toStart) + 1L;
            case YEARLY  -> ChronoUnit.YEARS.between(fromStart, toStart) + 1L;
        };
    }
}
