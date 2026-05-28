package com.mnemoscape.memory.admin;

import com.mnemoscape.common.admin.codec.IsoDateCodec;
import com.mnemoscape.common.admin.codec.TimeDimensionCodec;
import com.mnemoscape.common.admin.codec.TimeDimensionCodec.Dimension;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.memory.admin.config.AdminCacheConfig;
import com.mnemoscape.memory.admin.dto.ActiveUserBucket;
import com.mnemoscape.memory.admin.dto.ActiveUserRow;
import com.mnemoscape.memory.admin.dto.ContributorRow;
import com.mnemoscape.memory.admin.dto.EmotionDistribution;
import com.mnemoscape.memory.admin.dto.EmotionDistributionRow;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryByType;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryOverall;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryRow;
import com.mnemoscape.memory.admin.dto.HeatmapPoint;
import com.mnemoscape.memory.admin.dto.HeatmapRow;
import com.mnemoscape.memory.admin.dto.MemoryTrendBucket;
import com.mnemoscape.memory.admin.dto.MemoryTrendRow;
import com.mnemoscape.memory.admin.dto.TopContributor;
import com.mnemoscape.memory.admin.dto.TopContributorsResponse;
import com.mnemoscape.memory.admin.util.TimeBucketing;
import com.mnemoscape.memory.client.AuthServiceClient;
import com.mnemoscape.memory.client.dto.BatchUsernamesRequest;
import com.mnemoscape.memory.client.dto.BatchUsernamesResponse;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.memory.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregation service feeding the memory-service-side admin endpoints
 * (admin-dashboard tasks 7.8 / 7.9).
 *
 * <p>Two endpoints currently flow through this class:
 * <ul>
 *   <li>{@code GET /api/v1/admin/stats/active-user-counts} — internal endpoint
 *       called by auth-service via Feign to populate the dashboard's
 *       active-users panel (R6.6).</li>
 *   <li>{@code GET /api/v1/admin/stats/memory-trends} — public endpoint
 *       returning per-bucket {@code createdCount} / {@code modifiedCount}
 *       counts (R7).</li>
 * </ul>
 *
 * <p>Both endpoints share the same dimension / range validation pipeline:
 * <ol>
 *   <li>Parse {@code dimension} via {@link TimeDimensionCodec} — null /
 *       lowercase / aliases → 400 {@code INVALID_DIMENSION}.</li>
 *   <li>Parse {@code from} and {@code to} via {@link IsoDateCodec}; apply
 *       the dimension default window when both are absent (R6.5); fail-fast
 *       with 400 {@code INVALID_RANGE} otherwise.</li>
 *   <li>Validate bucket count via
 *       {@link TimeBucketing#requireBucketCountWithinLimit} — at most 366
 *       buckets per request (R6.4).</li>
 *   <li>Pull rows from {@link MemoryRepository}.</li>
 *   <li>Bucket and zero-fill via {@link TimeBucketing#zeroFillBuckets}.</li>
 * </ol>
 *
 * <p>Successful responses are memoized in the corresponding Redis cache
 * (60s TTL) per the design's {@code Caching Strategy} table.
 */
@Service
public class AdminStatsService {

    private static final Logger log = LoggerFactory.getLogger(AdminStatsService.class);

    /** Default top-contributors page size (R10.3). */
    private static final int DEFAULT_TOP_CONTRIBUTORS_LIMIT = 10;

    /** Maximum top-contributors page size (R10.3). */
    private static final int MAX_TOP_CONTRIBUTORS_LIMIT = 100;

    /** Maximum batch size accepted by auth-service.batchUsernames (R10.2). */
    private static final int AUTH_BATCH_LIMIT = 100;

    /** Heatmap step (degrees) per resolution (design §Heatmap Grid Algorithm). */
    private static final double HEATMAP_STEP_LOW = 5.0;
    private static final double HEATMAP_STEP_MEDIUM = 1.0;
    private static final double HEATMAP_STEP_HIGH = 0.25;

    private final MemoryRepository memoryRepository;
    /** Lazily injected via setter to keep top-contributors optional in tests. */
    private AuthServiceClient authServiceClient;
    /** Optional metrics — null in unit tests where AdminMetrics is not on the classpath. */
    private AdminMetrics adminMetrics;

    public AdminStatsService(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Autowired(required = false)
    public void setAuthServiceClient(AuthServiceClient authServiceClient) {
        this.authServiceClient = authServiceClient;
    }

    @Autowired(required = false)
    public void setAdminMetrics(AdminMetrics adminMetrics) {
        this.adminMetrics = adminMetrics;
    }

    // -- active-user-counts (task 7.8) --------------------------------------

    /**
     * Internal aggregation feeding {@code /admin/stats/active-user-counts}.
     *
     * <p>Cache key composition: {@code dimension|from|to}. The cache is
     * scoped to {@link AdminCacheConfig#CACHE_ACTIVE_USERS} (TTL 60s) per
     * Requirements 14.1 / 14.2.
     *
     * @return the zero-filled bucket series, one entry per bucket, ascending
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_ACTIVE_USERS,
            key = "T(com.mnemoscape.memory.admin.AdminStatsService).cacheKey(#dimensionRaw, #fromRaw, #toRaw)",
            sync = true)
    public List<ActiveUserBucket> aggregateActiveUserCounts(
            String dimensionRaw, String fromRaw, String toRaw) {

        Dimension dim = parseDimension(dimensionRaw);
        LocalDate to = parseToOrToday(toRaw);
        LocalDate from = parseFromOrDefault(fromRaw, dim, to);
        if (from.isAfter(to)) {
            throw new BizException(400, "INVALID_RANGE");
        }
        try {
            TimeBucketing.requireBucketCountWithinLimit(dim, from, to);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "INVALID_RANGE");
        }

        LocalDateTime fromDt = from.atStartOfDay();
        // Exclusive upper bound so the SQL ">= fromDt AND < toDt" predicate
        // matches the bucket-aligned semantics consistently.
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();
        List<ActiveUserRow> rows = memoryRepository.findActiveUserRowsBetween(fromDt, toDt);

        // Same user counted at most once per bucket — this is "active users",
        // not "memory events" (R6.6).
        Map<String, Set<String>> bucketUsers = new HashMap<>();
        for (ActiveUserRow row : rows) {
            LocalDateTime activity = row.getLatestActivityAt();
            if (activity == null) {
                continue;
            }
            String key = TimeBucketing.formatBucket(
                    dim, TimeBucketing.bucketStart(dim, activity));
            bucketUsers.computeIfAbsent(key, k -> new HashSet<>()).add(row.getUserId());
        }

        Map<String, Long> rawCounts = new HashMap<>(bucketUsers.size() * 2);
        for (Map.Entry<String, Set<String>> e : bucketUsers.entrySet()) {
            rawCounts.put(e.getKey(), (long) e.getValue().size());
        }

        List<TimeBucketing.Bucket> filled = TimeBucketing.zeroFillBuckets(dim, from, to, rawCounts);
        List<ActiveUserBucket> out = new ArrayList<>(filled.size());
        for (TimeBucketing.Bucket b : filled) {
            out.add(new ActiveUserBucket(b.bucket(), b.count()));
        }
        return Collections.unmodifiableList(out);
    }

    // -- memory-trends (task 7.9) -------------------------------------------

    /**
     * Aggregation feeding {@code /admin/stats/memory-trends}.
     *
     * <p>Cache key composition: {@code dimension|from|to}. The cache is
     * scoped to {@link AdminCacheConfig#CACHE_MEMORY_TRENDS} (TTL 60s) per
     * Requirements 14.1 / 14.2.
     *
     * <p>Modification accounting matches the design (R7.2):
     * {@code modifiedCount} only counts memories whose {@code updatedAt}
     * is strictly greater than {@code createdAt} so initial inserts are
     * never double-counted.
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_MEMORY_TRENDS,
            key = "T(com.mnemoscape.memory.admin.AdminStatsService).cacheKey(#dimensionRaw, #fromRaw, #toRaw)",
            sync = true)
    public List<MemoryTrendBucket> aggregateMemoryTrends(
            String dimensionRaw, String fromRaw, String toRaw) {

        Dimension dim = parseDimension(dimensionRaw);
        LocalDate to = parseToOrToday(toRaw);
        LocalDate from = parseFromOrDefault(fromRaw, dim, to);
        if (from.isAfter(to)) {
            throw new BizException(400, "INVALID_RANGE");
        }
        try {
            TimeBucketing.requireBucketCountWithinLimit(dim, from, to);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "INVALID_RANGE");
        }

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();
        List<MemoryTrendRow> rows = memoryRepository.findTrendRows(fromDt, toDt);

        Map<String, Long> created = new HashMap<>();
        Map<String, Long> modified = new HashMap<>();
        for (MemoryTrendRow row : rows) {
            LocalDateTime createdAt = row.getCreatedAt();
            if (createdAt != null
                    && !createdAt.isBefore(fromDt) && createdAt.isBefore(toDt)) {
                String key = TimeBucketing.formatBucket(
                        dim, TimeBucketing.bucketStart(dim, createdAt));
                created.merge(key, 1L, Long::sum);
            }
            LocalDateTime updatedAt = row.getUpdatedAt();
            if (createdAt != null && updatedAt != null && updatedAt.isAfter(createdAt)
                    && !updatedAt.isBefore(fromDt) && updatedAt.isBefore(toDt)) {
                String key = TimeBucketing.formatBucket(
                        dim, TimeBucketing.bucketStart(dim, updatedAt));
                modified.merge(key, 1L, Long::sum);
            }
        }

        // Use TimeBucketing.bucketSeries so the bucket key list is identical
        // to the active-users path; this also enforces the 366-bucket cap a
        // second time defensively.
        List<LocalDateTime> series = TimeBucketing.bucketSeries(dim, from, to);
        List<MemoryTrendBucket> out = new ArrayList<>(series.size());
        for (LocalDateTime start : series) {
            String key = TimeBucketing.formatBucket(dim, start);
            long c = created.getOrDefault(key, 0L);
            long m = modified.getOrDefault(key, 0L);
            out.add(new MemoryTrendBucket(key, c, m));
        }
        return Collections.unmodifiableList(out);
    }

    // -- shared helpers -----------------------------------------------------

    /** Cache key composer surfaced as a static helper for SpEL access. */
    public static String cacheKey(String dimensionRaw, String fromRaw, String toRaw) {
        StringBuilder sb = new StringBuilder();
        sb.append(dimensionRaw == null ? "" : dimensionRaw);
        sb.append('|');
        sb.append(fromRaw == null ? "" : fromRaw);
        sb.append('|');
        sb.append(toRaw == null ? "" : toRaw);
        return sb.toString();
    }

    private static Dimension parseDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(400, "INVALID_DIMENSION");
        }
        return TimeDimensionCodec.tryParse(raw)
                .orElseThrow(() -> new BizException(400, "INVALID_DIMENSION"));
    }

    private static LocalDate parseToOrToday(String toRaw) {
        if (toRaw == null || toRaw.isBlank()) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        return IsoDateCodec.tryParse(toRaw)
                .orElseThrow(() -> new BizException(400, "INVALID_RANGE"));
    }

    private static LocalDate parseFromOrDefault(String fromRaw, Dimension dim, LocalDate to) {
        if (fromRaw == null || fromRaw.isBlank()) {
            return TimeBucketing.defaultFrom(dim, to);
        }
        return IsoDateCodec.tryParse(fromRaw)
                .orElseThrow(() -> new BizException(400, "INVALID_RANGE"));
    }

    // -- emotion-distribution (task 7.11) -----------------------------------

    /**
     * Aggregate the eight-component mean emotion vector across PUBLIC
     * memories in the requested window (R8.1–R8.4).
     *
     * <p>The window defaults to the past 365 days when both range parameters
     * are omitted — the design does not require a specific default for this
     * endpoint, so we picked a value that captures a representative slice of
     * activity without forcing a full-table scan.
     *
     * <p>When {@code sampleSize == 0} (no qualifying memories) every component
     * is forced to {@code 0.0} per Requirement 8.3 so the front-end's radar
     * chart does not crash on null components.
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_EMOTION_DISTRIBUTION,
            key = "T(com.mnemoscape.memory.admin.AdminStatsService).emotionCacheKey(#fromRaw, #toRaw)",
            sync = true)
    public EmotionDistribution aggregateEmotionDistribution(String fromRaw, String toRaw) {
        LocalDate to = parseToOrToday(toRaw);
        LocalDate from = (fromRaw == null || fromRaw.isBlank())
                ? to.minusDays(365)
                : IsoDateCodec.tryParse(fromRaw)
                        .orElseThrow(() -> new BizException(400, "INVALID_RANGE"));
        if (from.isAfter(to)) {
            throw new BizException(400, "INVALID_RANGE");
        }

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();

        EmotionDistributionRow row = memoryRepository.emotionDistributionBetween(fromDt, toDt);
        long sampleSize = row == null ? 0L : row.getSampleSize();
        if (sampleSize == 0L || row == null) {
            return new EmotionDistribution(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        }
        return new EmotionDistribution(
                d(row.getAvgJoy()),
                d(row.getAvgSadness()),
                d(row.getAvgAnger()),
                d(row.getAvgFear()),
                d(row.getAvgSurprise()),
                d(row.getAvgNostalgia()),
                d(row.getAvgPeace()),
                d(row.getAvgMelancholy()),
                sampleSize);
    }

    /** SpEL-friendly cache key composer for emotion-distribution. */
    public static String emotionCacheKey(String fromRaw, String toRaw) {
        return (fromRaw == null ? "" : fromRaw) + '|' + (toRaw == null ? "" : toRaw);
    }

    /** Coerce a boxed Double into a primitive double, falling back to 0.0. */
    private static double d(Double v) {
        return v == null ? 0.0 : v;
    }

    // -- heatmap (task 7.13) ------------------------------------------------

    /** Allowed grid resolution tokens (R9.1). */
    public enum GridResolution { LOW, MEDIUM, HIGH }

    /**
     * Aggregate snap-to-grid heatmap points for the supplied resolution
     * (R9.1–R9.4).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Map the resolution token to a step (degrees).</li>
     *   <li>Run the native group-by query — already does
     *       {@code FLOOR(lat / step) * step + step/2} so the rows are
     *       quantised to grid centres.</li>
     *   <li>Find {@code maxRaw} and divide every count by it to produce
     *       {@code intensity ∈ (0, 1]} with {@code maxRaw → 1.0}.</li>
     *   <li>Sort {@code (lat, lon)} ascending so byte-stable cache hits
     *       across requests.</li>
     * </ol>
     *
     * <p>Empty result → empty list (no points with {@code intensity = 0}).
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_HEATMAP,
            key = "T(com.mnemoscape.memory.admin.AdminStatsService).heatmapCacheKey(#resolutionRaw)",
            sync = true)
    public List<HeatmapPoint> aggregateHeatmap(String resolutionRaw) {
        GridResolution resolution = parseResolution(resolutionRaw);
        double step = stepFor(resolution);
        List<HeatmapRow> rows = memoryRepository.heatmapBuckets(step);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        long maxRaw = 1L;
        for (HeatmapRow r : rows) {
            if (r.getRawCount() > maxRaw) {
                maxRaw = r.getRawCount();
            }
        }
        double max = (double) maxRaw;

        List<HeatmapPoint> out = new ArrayList<>(rows.size());
        for (HeatmapRow r : rows) {
            double intensity = Math.min(1.0, Math.max(0.0, r.getRawCount() / max));
            out.add(new HeatmapPoint(r.getLatBucket(), r.getLngBucket(), intensity));
        }
        // Stable order: lat ascending, then lon ascending. Matches the design
        // postcondition and gives byte-stable cache hits.
        out.sort((a, b) -> {
            int c = Double.compare(a.lat(), b.lat());
            return c != 0 ? c : Double.compare(a.lon(), b.lon());
        });
        return Collections.unmodifiableList(out);
    }

    /** SpEL-friendly cache key composer for heatmap. */
    public static String heatmapCacheKey(String resolutionRaw) {
        return resolutionRaw == null ? "MEDIUM" : resolutionRaw;
    }

    private static GridResolution parseResolution(String raw) {
        if (raw == null || raw.isBlank()) {
            return GridResolution.MEDIUM;
        }
        try {
            return GridResolution.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "INVALID_RESOLUTION");
        }
    }

    private static double stepFor(GridResolution r) {
        return switch (r) {
            case LOW -> HEATMAP_STEP_LOW;
            case MEDIUM -> HEATMAP_STEP_MEDIUM;
            case HIGH -> HEATMAP_STEP_HIGH;
        };
    }

    // -- top-contributors (task 7.17) ---------------------------------------

    /**
     * Aggregate top contributors with auth-service username enrichment
     * (R10.1–R10.4, R18.1).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Validate {@code limit} (1..100, default 10).</li>
     *   <li>Default range: past 30 days when both endpoints absent.</li>
     *   <li>Pull (userId, memoryCount) rows from the {@code memories} table
     *       ordered by count descending.</li>
     *   <li>Call {@code auth-service.batchUsernames} with all userIds; on
     *       success, populate {@code username}; on failure, fall back to
     *       {@code userId.substring(0,8)} for every row and set
     *       {@code degraded: true} (R18.1).</li>
     * </ol>
     *
     * <p>The cache key embeds {@code limit | from | to} so different
     * windows / page sizes have their own entries.
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_TOP_CONTRIBUTORS,
            key = "T(com.mnemoscape.memory.admin.AdminStatsService).topContributorsCacheKey(#limitRaw, #fromRaw, #toRaw)",
            sync = true)
    public TopContributorsResponse aggregateTopContributors(
            String limitRaw, String fromRaw, String toRaw) {

        int limit = parseTopContributorsLimit(limitRaw);
        LocalDate to = parseToOrToday(toRaw);
        LocalDate from = (fromRaw == null || fromRaw.isBlank())
                ? to.minusDays(30)
                : IsoDateCodec.tryParse(fromRaw)
                        .orElseThrow(() -> new BizException(400, "INVALID_RANGE"));
        if (from.isAfter(to)) {
            throw new BizException(400, "INVALID_RANGE");
        }

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();
        List<ContributorRow> rows = memoryRepository.findTopContributors(
                fromDt, toDt, PageRequest.of(0, limit));
        if (rows == null || rows.isEmpty()) {
            return TopContributorsResponse.ok(Collections.emptyList());
        }

        // Build the userId list — sliced to AUTH_BATCH_LIMIT in case some
        // future caller raises {@code limit} above 100 (defensive cap; current
        // code already validates {@code limit ≤ 100} above).
        List<String> userIds = new ArrayList<>(rows.size());
        for (ContributorRow row : rows) {
            userIds.add(row.getUserId());
        }
        if (userIds.size() > AUTH_BATCH_LIMIT) {
            userIds = userIds.subList(0, AUTH_BATCH_LIMIT);
        }

        Map<String, String> usernames = Map.of();
        boolean degraded = false;
        String degradationReason = null;
        if (authServiceClient != null && !userIds.isEmpty()) {
            try {
                ApiResponse<BatchUsernamesResponse> resp =
                        authServiceClient.batchUsernames(new BatchUsernamesRequest(userIds));
                if (resp != null && resp.getData() != null
                        && resp.getData().usernames() != null) {
                    usernames = resp.getData().usernames();
                }
            } catch (Exception e) {
                log.warn("auth-service batch-usernames lookup failed type={} message={}",
                        e.getClass().getSimpleName(), e.getMessage());
                degraded = true;
                degradationReason = "auth-service username lookup failed";
                if (adminMetrics != null) {
                    adminMetrics.degradedResponses("top-contributors", "auth-service").increment();
                }
            }
        }

        List<TopContributor> items = new ArrayList<>(rows.size());
        for (ContributorRow row : rows) {
            String uid = row.getUserId();
            String username = usernames.get(uid);
            if (username == null || username.isBlank()) {
                username = fallbackUsername(uid);
            }
            items.add(new TopContributor(uid, username, row.getMemoryCount()));
        }
        if (degraded) {
            return TopContributorsResponse.degraded(
                    Collections.unmodifiableList(items),
                    Collections.singletonList(degradationReason));
        }
        return TopContributorsResponse.ok(Collections.unmodifiableList(items));
    }

    /** SpEL-friendly cache key composer for top-contributors. */
    public static String topContributorsCacheKey(String limitRaw, String fromRaw, String toRaw) {
        return (limitRaw == null ? "" : limitRaw)
                + '|' + (fromRaw == null ? "" : fromRaw)
                + '|' + (toRaw == null ? "" : toRaw);
    }

    private static int parseTopContributorsLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TOP_CONTRIBUTORS_LIMIT;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(400, "INVALID_LIMIT");
        }
        if (parsed <= 0) {
            throw new BizException(400, "INVALID_LIMIT");
        }
        return Math.min(parsed, MAX_TOP_CONTRIBUTORS_LIMIT);
    }

    private static String fallbackUsername(String userId) {
        if (userId == null) {
            return "unknown";
        }
        return userId.length() <= 8 ? userId : userId.substring(0, 8);
    }

    // -- fragment-discovery (task 7.19) -------------------------------------

    /**
     * Overall (no groupBy) fragment discovery aggregation (R11.1, R11.2).
     *
     * <p>{@code discoveryRate = totalFragments == 0 ? 0.0 : discovered/total}.
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_FRAGMENT_DISCOVERY,
            key = "'overall'",
            sync = true)
    public FragmentDiscoveryOverall aggregateFragmentDiscoveryOverall() {
        FragmentDiscoveryRow row = memoryRepository.aggregateFragmentOverall();
        long total = row == null ? 0L : row.getTotalFragments();
        long discovered = row == null ? 0L : row.getDiscoveredFragments();
        double rate = total == 0L ? 0.0 : ((double) discovered) / total;
        return new FragmentDiscoveryOverall(total, discovered, rate);
    }

    /**
     * Per-type fragment discovery aggregation (R11.3).
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_FRAGMENT_DISCOVERY,
            key = "'byType'",
            sync = true)
    public List<FragmentDiscoveryByType> aggregateFragmentDiscoveryByType() {
        List<FragmentDiscoveryRow> rows = memoryRepository.aggregateFragmentByType();
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<FragmentDiscoveryByType> out = new ArrayList<>(rows.size());
        for (FragmentDiscoveryRow row : rows) {
            long total = row.getTotalFragments();
            long discovered = row.getDiscoveredFragments();
            double rate = total == 0L ? 0.0 : ((double) discovered) / total;
            out.add(new FragmentDiscoveryByType(
                    row.getFragmentType(),
                    total,
                    discovered,
                    rate));
        }
        return Collections.unmodifiableList(out);
    }
}
