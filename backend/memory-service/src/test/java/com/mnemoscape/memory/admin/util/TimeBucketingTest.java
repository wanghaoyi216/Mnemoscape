package com.mnemoscape.memory.admin.util;

import com.mnemoscape.common.admin.codec.TimeDimensionCodec.Dimension;
import com.mnemoscape.memory.admin.util.TimeBucketing.Bucket;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimeBucketing}. Property-based tests (Properties
 * 4 / 5 / 6 / 10) live in the optional jqwik suite under tasks 7.3 – 7.6.
 */
class TimeBucketingTest {

    @Nested
    class BucketStart {
        @Test
        void daily_snaps_to_utc_midnight_of_same_day() {
            LocalDateTime input = LocalDateTime.of(2026, 5, 24, 13, 45, 30);
            assertEquals(LocalDateTime.of(2026, 5, 24, 0, 0),
                    TimeBucketing.bucketStart(Dimension.DAILY, input));
        }

        @Test
        void weekly_snaps_to_iso_monday_for_midweek_input() {
            // Wednesday 2026-05-20 → Monday 2026-05-18
            LocalDateTime input = LocalDateTime.of(2026, 5, 20, 9, 0);
            assertEquals(LocalDateTime.of(2026, 5, 18, 0, 0),
                    TimeBucketing.bucketStart(Dimension.WEEKLY, input));
        }

        @Test
        void weekly_keeps_monday_when_input_is_already_monday() {
            LocalDateTime input = LocalDateTime.of(2026, 5, 18, 0, 0);
            assertEquals(input, TimeBucketing.bucketStart(Dimension.WEEKLY, input));
        }

        @Test
        void monthly_snaps_to_first_of_month() {
            LocalDateTime input = LocalDateTime.of(2026, 5, 15, 23, 59);
            assertEquals(LocalDateTime.of(2026, 5, 1, 0, 0),
                    TimeBucketing.bucketStart(Dimension.MONTHLY, input));
        }

        @Test
        void yearly_snaps_to_jan_1() {
            LocalDateTime input = LocalDateTime.of(2026, 7, 4, 12, 0);
            assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0),
                    TimeBucketing.bucketStart(Dimension.YEARLY, input));
        }
    }

    @Nested
    class NextBucketStart {
        @Test
        void daily_advances_one_day() {
            LocalDateTime in = LocalDateTime.of(2026, 5, 24, 0, 0);
            assertEquals(LocalDateTime.of(2026, 5, 25, 0, 0),
                    TimeBucketing.nextBucketStart(Dimension.DAILY, in));
        }

        @Test
        void weekly_advances_seven_days() {
            LocalDateTime in = LocalDateTime.of(2026, 5, 18, 0, 0);
            assertEquals(LocalDateTime.of(2026, 5, 25, 0, 0),
                    TimeBucketing.nextBucketStart(Dimension.WEEKLY, in));
        }

        @Test
        void monthly_advances_one_month_handling_lengths() {
            LocalDateTime in = LocalDateTime.of(2026, 1, 1, 0, 0);
            assertEquals(LocalDateTime.of(2026, 2, 1, 0, 0),
                    TimeBucketing.nextBucketStart(Dimension.MONTHLY, in));
        }

        @Test
        void yearly_advances_one_year() {
            LocalDateTime in = LocalDateTime.of(2026, 1, 1, 0, 0);
            assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0),
                    TimeBucketing.nextBucketStart(Dimension.YEARLY, in));
        }
    }

    @Nested
    class FormatAndParse {
        @Test
        void daily_format_is_yyyy_mm_dd() {
            assertEquals("2026-05-24",
                    TimeBucketing.formatBucket(Dimension.DAILY,
                            LocalDateTime.of(2026, 5, 24, 0, 0)));
        }

        @Test
        void weekly_format_is_yyyy_Www_with_iso_week_based_year() {
            // 2026-05-18 = ISO week 21 of week-based year 2026
            assertEquals("2026-W21",
                    TimeBucketing.formatBucket(Dimension.WEEKLY,
                            LocalDateTime.of(2026, 5, 18, 0, 0)));
        }

        @Test
        void weekly_format_uses_week_based_year_at_year_boundary() {
            // 2027-01-01 (Friday) belongs to ISO week 53 of week-based year 2026
            assertEquals("2026-W53",
                    TimeBucketing.formatBucket(Dimension.WEEKLY,
                            TimeBucketing.bucketStart(Dimension.WEEKLY,
                                    LocalDateTime.of(2027, 1, 1, 0, 0))));
        }

        @Test
        void monthly_format_is_yyyy_mm() {
            assertEquals("2026-05",
                    TimeBucketing.formatBucket(Dimension.MONTHLY,
                            LocalDateTime.of(2026, 5, 1, 0, 0)));
        }

        @Test
        void yearly_format_is_yyyy() {
            assertEquals("2026",
                    TimeBucketing.formatBucket(Dimension.YEARLY,
                            LocalDateTime.of(2026, 1, 1, 0, 0)));
        }

        @Test
        void parse_round_trips_for_every_dimension() {
            for (Dimension dim : Dimension.values()) {
                LocalDateTime start = TimeBucketing.bucketStart(dim,
                        LocalDateTime.of(2026, 5, 18, 0, 0));
                String key = TimeBucketing.formatBucket(dim, start);
                assertEquals(start, TimeBucketing.parseBucket(dim, key),
                        "round-trip failed for " + dim);
            }
        }

        @Test
        void parse_rejects_invalid_keys() {
            assertThrows(IllegalArgumentException.class,
                    () -> TimeBucketing.parseBucket(Dimension.DAILY, "2026/05/24"));
            assertThrows(IllegalArgumentException.class,
                    () -> TimeBucketing.parseBucket(Dimension.WEEKLY, "2026-W7"));
            assertThrows(IllegalArgumentException.class,
                    () -> TimeBucketing.parseBucket(Dimension.MONTHLY, "2026-5"));
            assertThrows(IllegalArgumentException.class,
                    () -> TimeBucketing.parseBucket(Dimension.YEARLY, "26"));
        }
    }

    @Nested
    class BucketSeries {
        @Test
        void single_day_yields_one_bucket() {
            LocalDate d = LocalDate.of(2026, 5, 24);
            List<LocalDateTime> series = TimeBucketing.bucketSeries(Dimension.DAILY, d, d);
            assertEquals(1, series.size());
            assertEquals(d.atStartOfDay(), series.get(0));
        }

        @Test
        void daily_30_days_yields_31_buckets_inclusive() {
            LocalDate to = LocalDate.of(2026, 5, 30);
            LocalDate from = to.minusDays(30);
            assertEquals(31, TimeBucketing.bucketSeries(Dimension.DAILY, from, to).size());
        }

        @Test
        void weekly_aligns_start_to_iso_monday() {
            // from is Wednesday 2026-05-20 → first bucket starts Monday 2026-05-18
            LocalDate from = LocalDate.of(2026, 5, 20);
            LocalDate to = LocalDate.of(2026, 6, 3);
            List<LocalDateTime> series = TimeBucketing.bucketSeries(Dimension.WEEKLY, from, to);
            assertEquals(LocalDateTime.of(2026, 5, 18, 0, 0), series.get(0));
            assertEquals(3, series.size());
        }

        @Test
        void series_is_strictly_ascending() {
            List<LocalDateTime> series = TimeBucketing.bucketSeries(
                    Dimension.MONTHLY, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 12, 1));
            for (int i = 1; i < series.size(); i++) {
                assertTrue(series.get(i).isAfter(series.get(i - 1)));
            }
        }
    }

    @Nested
    class BucketCountLimit {
        @Test
        void rejects_when_from_is_after_to() {
            assertThrows(IllegalArgumentException.class,
                    () -> TimeBucketing.requireBucketCountWithinLimit(
                            Dimension.DAILY,
                            LocalDate.of(2026, 5, 24),
                            LocalDate.of(2026, 5, 23)));
        }

        @Test
        void rejects_when_daily_range_exceeds_366() {
            LocalDate from = LocalDate.of(2025, 1, 1);
            LocalDate to = from.plusDays(366); // 367 buckets inclusive
            assertThrows(IllegalArgumentException.class,
                    () -> TimeBucketing.requireBucketCountWithinLimit(Dimension.DAILY, from, to));
        }

        @Test
        void accepts_exactly_366_buckets() {
            LocalDate from = LocalDate.of(2025, 1, 1);
            LocalDate to = from.plusDays(365); // 366 inclusive
            TimeBucketing.requireBucketCountWithinLimit(Dimension.DAILY, from, to);
        }
    }

    @Nested
    class DefaultFrom {
        @Test
        void daily_offsets_30_days() {
            LocalDate to = LocalDate.of(2026, 5, 24);
            assertEquals(to.minusDays(30), TimeBucketing.defaultFrom(Dimension.DAILY, to));
        }

        @Test
        void weekly_offsets_12_weeks() {
            LocalDate to = LocalDate.of(2026, 5, 24);
            assertEquals(to.minusWeeks(12), TimeBucketing.defaultFrom(Dimension.WEEKLY, to));
        }

        @Test
        void monthly_offsets_12_months() {
            LocalDate to = LocalDate.of(2026, 5, 24);
            assertEquals(to.minusMonths(12), TimeBucketing.defaultFrom(Dimension.MONTHLY, to));
        }

        @Test
        void yearly_offsets_5_years() {
            LocalDate to = LocalDate.of(2026, 5, 24);
            assertEquals(to.minusYears(5), TimeBucketing.defaultFrom(Dimension.YEARLY, to));
        }
    }

    @Nested
    class ZeroFillBuckets {
        @Test
        void fills_missing_buckets_with_zero() {
            LocalDate from = LocalDate.of(2026, 5, 22);
            LocalDate to = LocalDate.of(2026, 5, 24);
            Map<String, Long> raw = Map.of("2026-05-23", 7L);

            List<Bucket> filled = TimeBucketing.zeroFillBuckets(Dimension.DAILY, from, to, raw);

            assertEquals(3, filled.size());
            assertEquals(new Bucket("2026-05-22", 0L), filled.get(0));
            assertEquals(new Bucket("2026-05-23", 7L), filled.get(1));
            assertEquals(new Bucket("2026-05-24", 0L), filled.get(2));
        }

        @Test
        void series_size_equals_bucket_count() {
            LocalDate from = LocalDate.of(2026, 1, 15);
            LocalDate to = LocalDate.of(2026, 4, 10);
            List<Bucket> filled = TimeBucketing.zeroFillBuckets(
                    Dimension.MONTHLY, from, to, Map.of());
            assertEquals(4, filled.size()); // Jan, Feb, Mar, Apr
            assertEquals("2026-01", filled.get(0).bucket());
            assertEquals("2026-04", filled.get(3).bucket());
            for (Bucket b : filled) {
                assertEquals(0L, b.count());
            }
        }

        @Test
        void rejects_negative_raw_count() {
            LocalDate d = LocalDate.of(2026, 5, 24);
            Map<String, Long> raw = Map.of("2026-05-24", -1L);
            assertThrows(IllegalArgumentException.class,
                    () -> TimeBucketing.zeroFillBuckets(Dimension.DAILY, d, d, raw));
        }

        @Test
        void result_is_sorted_lexicographically_matching_chronology() {
            LocalDate from = LocalDate.of(2025, 12, 28);
            LocalDate to = LocalDate.of(2026, 1, 5);
            List<Bucket> filled = TimeBucketing.zeroFillBuckets(
                    Dimension.DAILY, from, to, Map.of());
            for (int i = 1; i < filled.size(); i++) {
                assertTrue(filled.get(i).bucket().compareTo(filled.get(i - 1).bucket()) > 0,
                        "expected strict lexicographic order, got "
                                + filled.get(i - 1).bucket() + " then " + filled.get(i).bucket());
            }
        }
    }
}
