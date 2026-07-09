package com.mnemoscape.memory.admin;

import com.mnemoscape.common.admin.AdminAuditLogger;
import com.mnemoscape.common.admin.QueryHasher;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.memory.admin.dto.ActiveUserBucket;
import com.mnemoscape.memory.admin.dto.EmotionDistribution;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryByType;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryOverall;
import com.mnemoscape.memory.admin.dto.HeatmapPoint;
import com.mnemoscape.memory.admin.dto.MemoryTrendBucket;
import com.mnemoscape.memory.admin.dto.TopContributorsResponse;
import com.mnemoscape.common.ratelimit.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory-service-side admin aggregation endpoints (admin-dashboard tasks
 * 7.8 / 7.9).
 *
 * <p>Two endpoints are wired here:
 * <ul>
 *   <li>{@code GET /api/v1/admin/stats/active-user-counts} — internal endpoint
 *       called by auth-service via Feign. Returns the time-bucketed
 *       distinct-user counts feeding the dashboard's
 *       {@code admin.stats.active-users} panel (R6.6).</li>
 *   <li>{@code GET /api/v1/admin/stats/memory-trends} — public endpoint
 *       returning per-bucket {@code createdCount} / {@code modifiedCount}
 *       counts for the dashboard's memory-trends panel (R7).</li>
 * </ul>
 *
 * <p>Both endpoints share the same audit / metric instrumentation pattern:
 * <ul>
 *   <li>Latency captured via {@link AdminMetrics#uncachedLatency(String)}
 *       (R14.5).</li>
 *   <li>One {@code admin-audit} structured log line per request, regardless
 *       of success or failure, emitted from the {@code finally} branch
 *       (R15.4).</li>
 *   <li>Query parameters never appear in either log; only the
 *       {@link QueryHasher} digest does (R15.5).</li>
 *   <li>Failure paths re-throw {@link BizException} so
 *       {@code GlobalExceptionHandler} produces the canonical error envelope
 *       (R13.1).</li>
 * </ul>
 *
 * <p>Both endpoints sit under {@code /api/v1/admin/**} and are gated by
 * {@code SecurityConfig.adminSecurityFilterChain} which enforces
 * {@code hasRole('ADMIN')} as the second-line defense behind the gateway's
 * AdminGuardFilter (R3.2).
 */
@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminStatsController {

    private static final Logger log = LoggerFactory.getLogger(AdminStatsController.class);

    private static final String ENDPOINT_KEY_ACTIVE_USERS = "active-user-counts";
    private static final String ENDPOINT_PATH_ACTIVE_USERS = "/api/v1/admin/stats/active-user-counts";

    private static final String ENDPOINT_KEY_MEMORY_TRENDS = "memory-trends";
    private static final String ENDPOINT_PATH_MEMORY_TRENDS = "/api/v1/admin/stats/memory-trends";

    private static final String ENDPOINT_KEY_EMOTION = "emotion-distribution";
    private static final String ENDPOINT_PATH_EMOTION = "/api/v1/admin/stats/emotion-distribution";

    private static final String ENDPOINT_KEY_HEATMAP = "heatmap";
    private static final String ENDPOINT_PATH_HEATMAP = "/api/v1/admin/stats/heatmap";

    private static final String ENDPOINT_KEY_TOP_CONTRIBUTORS = "top-contributors";
    private static final String ENDPOINT_PATH_TOP_CONTRIBUTORS = "/api/v1/admin/stats/top-contributors";

    private static final String ENDPOINT_KEY_FRAGMENTS = "fragment-discovery";
    private static final String ENDPOINT_PATH_FRAGMENTS = "/api/v1/admin/stats/fragment-discovery";

    /** Header injected by the gateway after JWT validation; null on direct internal calls. */
    private static final String USER_ID_HEADER = "X-User-Id";

    private final AdminStatsService adminStatsService;
    private final AdminMetrics adminMetrics;

    public AdminStatsController(AdminStatsService adminStatsService, AdminMetrics adminMetrics) {
        this.adminStatsService = adminStatsService;
        this.adminMetrics = adminMetrics;
    }

    /**
     * {@code GET /api/v1/admin/stats/active-user-counts?dimension&from&to}
     *
     * <p>Internal endpoint — invoked by auth-service via Feign. Returns the
     * zero-filled bucket series matching the {@code dimension} and (optional)
     * range. Defaults to the dimension's standard window when both range
     * parameters are absent (R6.5).
     */
    @RateLimit(key = "admin:stats:active-user-counts", limit = 30, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Admin 统计拉取过于频繁，请稍后再试")
    @GetMapping("/active-user-counts")
    public ResponseEntity<ApiResponse<List<ActiveUserBucket>>> activeUserCounts(
            @RequestParam(value = "dimension", required = false) String dimension,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            HttpServletRequest req) {

        long startNs = System.nanoTime();
        int responseStatus = 200;
        String queryHash = hashRange(dimension, from, to);
        try {
            List<ActiveUserBucket> data = adminMetrics.uncachedLatency(ENDPOINT_KEY_ACTIVE_USERS)
                    .recordCallable(() -> adminStatsService.aggregateActiveUserCounts(dimension, from, to));
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            responseStatus = 500;
            throw BizException.internalError("Admin stats aggregation failed", e);
        } finally {
            writeAudit(req, ENDPOINT_PATH_ACTIVE_USERS, queryHash, responseStatus, startNs);
        }
    }

    /**
     * {@code GET /api/v1/admin/stats/memory-trends?dimension&from&to}
     *
     * <p>Public dashboard endpoint. Same dimension / range semantics as
     * {@link #activeUserCounts}; the response payload changes from
     * {@link ActiveUserBucket} to {@link MemoryTrendBucket} (carrying both
     * created and modified counts).
     */
    @RateLimit(key = "admin:stats:memory-trends", limit = 30, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Admin 统计拉取过于频繁，请稍后再试")
    @GetMapping("/memory-trends")
    public ResponseEntity<ApiResponse<List<MemoryTrendBucket>>> memoryTrends(
            @RequestParam(value = "dimension", required = false) String dimension,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            HttpServletRequest req) {

        long startNs = System.nanoTime();
        int responseStatus = 200;
        String queryHash = hashRange(dimension, from, to);
        try {
            List<MemoryTrendBucket> data = adminMetrics.uncachedLatency(ENDPOINT_KEY_MEMORY_TRENDS)
                    .recordCallable(() -> adminStatsService.aggregateMemoryTrends(dimension, from, to));
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            responseStatus = 500;
            throw BizException.internalError("Admin stats aggregation failed", e);
        } finally {
            writeAudit(req, ENDPOINT_PATH_MEMORY_TRENDS, queryHash, responseStatus, startNs);
        }
    }

    /**
     * Shared {@code admin-audit} writer. Catches its own exceptions so that
     * a faulty audit appender (disk full / config typo) cannot break the
     * response path.
     */
    private static void writeAudit(HttpServletRequest req, String endpointPath,
                                   String queryHash, int status, long startNs) {
        long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
        String adminUserId = req.getHeader(USER_ID_HEADER);
        try {
            AdminAuditLogger.logAccess(adminUserId, endpointPath, queryHash, status, latencyMs);
        } catch (Exception e) {
            log.debug("admin-audit logging failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Hash the {@code (dimension, from, to)} triple per the design's hash-input table. */
    private static String hashRange(String dimension, String from, String to) {
        Map<String, String> params = new HashMap<>();
        params.put("dimension", dimension == null ? "" : dimension);
        params.put("from", from == null ? "" : from);
        params.put("to", to == null ? "" : to);
        return QueryHasher.hash(params);
    }

    // -- emotion-distribution (task 7.11) -----------------------------------

    /**
     * {@code GET /api/v1/admin/stats/emotion-distribution?from&to}
     *
     * <p>Eight-component mean emotion vector across PUBLIC memories in
     * {@code [from, to]}. Defaults to the past 365 days when both range
     * parameters are absent.
     */
    @RateLimit(key = "admin:stats:emotion-distribution", limit = 20, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Admin 统计拉取过于频繁，请稍后再试")
    @GetMapping("/emotion-distribution")
    public ResponseEntity<ApiResponse<EmotionDistribution>> emotionDistribution(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            HttpServletRequest req) {

        long startNs = System.nanoTime();
        int responseStatus = 200;
        Map<String, String> params = new HashMap<>();
        params.put("from", from == null ? "" : from);
        params.put("to", to == null ? "" : to);
        String queryHash = QueryHasher.hash(params);
        try {
            EmotionDistribution data = adminMetrics.uncachedLatency(ENDPOINT_KEY_EMOTION)
                    .recordCallable(() -> adminStatsService.aggregateEmotionDistribution(from, to));
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            responseStatus = 500;
            throw BizException.internalError("Admin stats aggregation failed", e);
        } finally {
            writeAudit(req, ENDPOINT_PATH_EMOTION, queryHash, responseStatus, startNs);
        }
    }

    // -- heatmap (task 7.13) ------------------------------------------------

    /**
     * {@code GET /api/v1/admin/stats/heatmap?gridResolution}
     *
     * <p>Snap-to-grid heatmap at one of three quantisation levels:
     * {@code LOW} (5°), {@code MEDIUM} (1°), {@code HIGH} (0.25°). Defaults to
     * {@code MEDIUM} when omitted (R9.1).
     */
    @RateLimit(key = "admin:stats:heatmap", limit = 10, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Admin 统计拉取过于频繁，请稍后再试")
    @GetMapping("/heatmap")
    public ResponseEntity<ApiResponse<List<HeatmapPoint>>> heatmap(
            @RequestParam(value = "gridResolution", required = false) String gridResolution,
            HttpServletRequest req) {

        long startNs = System.nanoTime();
        int responseStatus = 200;
        Map<String, String> params = new HashMap<>();
        params.put("gridResolution", gridResolution == null ? "" : gridResolution);
        String queryHash = QueryHasher.hash(params);
        try {
            List<HeatmapPoint> data = adminMetrics.uncachedLatency(ENDPOINT_KEY_HEATMAP)
                    .recordCallable(() -> adminStatsService.aggregateHeatmap(gridResolution));
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            responseStatus = 500;
            throw BizException.internalError("Admin stats aggregation failed", e);
        } finally {
            writeAudit(req, ENDPOINT_PATH_HEATMAP, queryHash, responseStatus, startNs);
        }
    }

    // -- top-contributors (task 7.17) ---------------------------------------

    /**
     * {@code GET /api/v1/admin/stats/top-contributors?limit&from&to}
     *
     * <p>Top {@code limit} contributors (default 10, capped 100) ordered by
     * memory count descending, joined with auth-service usernames. On
     * username lookup failure the response is degraded with usernames
     * falling back to {@code userId.substring(0,8)}.
     */
    @RateLimit(key = "admin:stats:top-contributors", limit = 30, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Admin 统计拉取过于频繁，请稍后再试")
    @GetMapping("/top-contributors")
    public ResponseEntity<ApiResponse<TopContributorsResponse>> topContributors(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            HttpServletRequest req) {

        long startNs = System.nanoTime();
        int responseStatus = 200;
        Map<String, String> params = new HashMap<>();
        params.put("limit", limit == null ? "" : limit);
        params.put("from", from == null ? "" : from);
        params.put("to", to == null ? "" : to);
        String queryHash = QueryHasher.hash(params);
        try {
            TopContributorsResponse data = adminMetrics.uncachedLatency(ENDPOINT_KEY_TOP_CONTRIBUTORS)
                    .recordCallable(() -> adminStatsService.aggregateTopContributors(limit, from, to));
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            responseStatus = 500;
            throw BizException.internalError("Admin stats aggregation failed", e);
        } finally {
            writeAudit(req, ENDPOINT_PATH_TOP_CONTRIBUTORS, queryHash, responseStatus, startNs);
        }
    }

    // -- fragment-discovery (task 7.19) -------------------------------------

    /**
     * {@code GET /api/v1/admin/stats/fragment-discovery?groupBy?}
     *
     * <p>{@code groupBy=fragmentType} → returns a {@code List<FragmentDiscoveryByType>}.
     * Otherwise → returns a single {@code FragmentDiscoveryOverall} record.
     *
     * <p>The two response shapes share the same envelope code path and the
     * same {@code admin.fragment-discovery} cache (different keys).
     */
    @RateLimit(key = "admin:stats:fragment-discovery", limit = 20, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Admin 统计拉取过于频繁，请稍后再试")
    @GetMapping("/fragment-discovery")
    public ResponseEntity<ApiResponse<Object>> fragmentDiscovery(
            @RequestParam(value = "groupBy", required = false) String groupBy,
            HttpServletRequest req) {

        long startNs = System.nanoTime();
        int responseStatus = 200;
        Map<String, String> params = new HashMap<>();
        params.put("groupBy", groupBy == null ? "" : groupBy);
        String queryHash = QueryHasher.hash(params);
        try {
            Object data;
            if ("fragmentType".equals(groupBy)) {
                data = adminMetrics.uncachedLatency(ENDPOINT_KEY_FRAGMENTS)
                        .recordCallable(adminStatsService::aggregateFragmentDiscoveryByType);
            } else {
                data = adminMetrics.uncachedLatency(ENDPOINT_KEY_FRAGMENTS)
                        .recordCallable(adminStatsService::aggregateFragmentDiscoveryOverall);
            }
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            responseStatus = 500;
            throw BizException.internalError("Admin stats aggregation failed", e);
        } finally {
            writeAudit(req, ENDPOINT_PATH_FRAGMENTS, queryHash, responseStatus, startNs);
        }
    }
}
