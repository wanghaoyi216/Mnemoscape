package com.mnemoscape.resonance.admin;

import com.mnemoscape.common.admin.AdminAuditLogger;
import com.mnemoscape.common.admin.QueryHasher;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.resonance.admin.dto.ResonanceOverview;
import com.mnemoscape.resonance.admin.dto.ResonanceTopEdge;
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
 * Resonance-service-side admin aggregation endpoints
 * (admin-dashboard task 8.2 / Requirements 12.1–12.3, 13–15, 18).
 *
 * <p>Two read-only endpoints flow through this class:
 * <ul>
 *   <li>{@code GET /api/v1/admin/stats/resonance-overview} — KPI overview</li>
 *   <li>{@code GET /api/v1/admin/stats/resonance-top?limit=N} — top edges</li>
 * </ul>
 *
 * <p>Both endpoints are gated by
 * {@code SecurityConfig.adminSecurityFilterChain} which enforces
 * {@code hasRole('ADMIN')} as a second-line defense behind the gateway's
 * AdminGuardFilter (R3.2).
 *
 * <p>Cross-cutting instrumentation per design §Observability:
 * <ul>
 *   <li>{@link AdminMetrics#uncachedLatency(String)} captures wall-clock
 *       latency — cache hits short-circuit the timer, so this measures the
 *       cost of producing fresh data only (R14.5).</li>
 *   <li>One {@code admin-audit} JSON record per request, including failure
 *       paths, emitted from the {@code finally} branch (R15.4 / R15.5).</li>
 *   <li>The {@code queryHash} field embeds only canonicalised query
 *       parameters — never auth headers, never raw {@code limit} value with
 *       PII attached (R15.5).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminResonanceController {

    private static final Logger log = LoggerFactory.getLogger(AdminResonanceController.class);

    private static final String ENDPOINT_KEY_OVERVIEW = "resonance-overview";
    private static final String ENDPOINT_PATH_OVERVIEW = "/api/v1/admin/stats/resonance-overview";

    private static final String ENDPOINT_KEY_TOP = "resonance-top";
    private static final String ENDPOINT_PATH_TOP = "/api/v1/admin/stats/resonance-top";

    /** Header injected by the gateway after JWT validation. */
    private static final String USER_ID_HEADER = "X-User-Id";

    private final AdminResonanceService adminResonanceService;
    private final AdminMetrics adminMetrics;

    public AdminResonanceController(AdminResonanceService adminResonanceService,
                                    AdminMetrics adminMetrics) {
        this.adminResonanceService = adminResonanceService;
        this.adminMetrics = adminMetrics;
    }

    /**
     * {@code GET /api/v1/admin/stats/resonance-overview}
     *
     * <p>No query parameters. Returns the resonance KPI overview record
     * carrying total edge count, weighted average score, and a status
     * breakdown map.
     */
    @RateLimit(key = "admin:stats:resonance-overview", limit = 30, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Admin overview 拉取过于频繁，请稍后再试")
    @GetMapping("/resonance-overview")
    public ResponseEntity<ApiResponse<ResonanceOverview>> overview(HttpServletRequest req) {
        long startNs = System.nanoTime();
        int responseStatus = 200;
        // Hash an empty parameter map so the field is always populated and
        // identifies the endpoint variant in the audit log (R15.5).
        String queryHash = QueryHasher.hash(Map.of());
        try {
            ResonanceOverview data = adminMetrics.uncachedLatency(ENDPOINT_KEY_OVERVIEW)
                    .recordCallable(adminResonanceService::overview);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            responseStatus = 500;
            throw new RuntimeException(e);
        } finally {
            writeAudit(req, ENDPOINT_PATH_OVERVIEW, queryHash, responseStatus, startNs);
        }
    }

    /**
     * {@code GET /api/v1/admin/stats/resonance-top?limit=N}
     *
     * <p>Returns the top {@code N} resonance edges ordered by similarity
     * score descending. {@code limit} defaults to 20 and is hard-capped at
     * 100 inside the service layer.
     */
    @RateLimit(key = "admin:stats:resonance-top", limit = 30, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Admin top 拉取过于频繁，请稍后再试")
    @GetMapping("/resonance-top")
    public ResponseEntity<ApiResponse<List<ResonanceTopEdge>>> topEdges(
            @RequestParam(value = "limit", required = false) String limit,
            HttpServletRequest req) {

        long startNs = System.nanoTime();
        int responseStatus = 200;
        String queryHash = hashLimit(limit);
        try {
            List<ResonanceTopEdge> data = adminMetrics.uncachedLatency(ENDPOINT_KEY_TOP)
                    .recordCallable(() -> adminResonanceService.topEdges(limit));
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            responseStatus = 500;
            throw new RuntimeException(e);
        } finally {
            writeAudit(req, ENDPOINT_PATH_TOP, queryHash, responseStatus, startNs);
        }
    }

    /** Build the hash input for the resonance-top endpoint per the design table. */
    private static String hashLimit(String limit) {
        Map<String, String> params = new HashMap<>();
        params.put("limit", limit == null ? "" : limit);
        return QueryHasher.hash(params);
    }

    /**
     * Shared {@code admin-audit} writer. Catches its own exceptions so a
     * faulty audit appender never breaks the response path.
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
}
