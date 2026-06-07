package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.model.dto.ActiveUserBucket;
import com.mnemoscape.auth.service.AdminStatsService;
import com.mnemoscape.common.admin.AdminAuditLogger;
import com.mnemoscape.common.admin.QueryHasher;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.exception.BizException;
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
 * Public-facing admin aggregation endpoints owned by auth-service
 * (admin-dashboard task 6.6).
 *
 * <p>Currently exposes:
 * <ul>
 *   <li>{@code GET /api/v1/admin/stats/active-users?dimension&from&to} — the
 *       time-bucketed active-user count, computed by memory-service via
 *       Feign. R6 / R13 / R14.</li>
 * </ul>
 *
 * <p>Cross-cutting concerns:
 * <ul>
 *   <li><b>Audit</b>: every successful or failed call writes one structured
 *       log line to the {@code admin-audit} logger (R15.4) carrying
 *       {@code adminUserId}, {@code endpoint} (path only, never query string),
 *       {@code queryHash} (SHA-256 prefix over the query map — R15.5),
 *       {@code status}, and {@code latencyMs}.</li>
 *   <li><b>Metrics</b>: {@link AdminMetrics#uncachedLatency(String)} times
 *       the call (cache hit / miss bookkeeping is centralised in
 *       {@code BypassCache}).</li>
 *   <li><b>Errors</b>: {@link BizException} thrown by the service layer
 *       propagates to {@link com.mnemoscape.common.exception.GlobalExceptionHandler}
 *       which produces the canonical {@code ApiResponse.error(...)} envelope
 *       with the appropriate HTTP status. The audit record is still written
 *       in the {@code finally} branch so reject paths are observable.</li>
 * </ul>
 *
 * <p>The response payload is the strict whitelist {@link ActiveUserBucket}
 * record (only {@code bucket} and {@code activeUserCount} fields). No
 * privacy-sensitive memory data leaves auth-service through this surface
 * (R15.1 / R15.2).
 */
@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminStatsController {

    private static final Logger log = LoggerFactory.getLogger(AdminStatsController.class);

    /** Short endpoint key for {@link AdminMetrics} tags / {@code admin-audit} field. */
    private static final String ENDPOINT_KEY_ACTIVE_USERS = "active-users";

    /** Canonical path field on {@code admin-audit} log lines. */
    private static final String ENDPOINT_PATH_ACTIVE_USERS = "/api/v1/admin/stats/active-users";

    /** Header injected by the gateway after JWT validation. */
    private static final String USER_ID_HEADER = "X-User-Id";

    private final AdminStatsService adminStatsService;
    private final AdminMetrics adminMetrics;

    public AdminStatsController(AdminStatsService adminStatsService, AdminMetrics adminMetrics) {
        this.adminStatsService = adminStatsService;
        this.adminMetrics = adminMetrics;
    }

    /**
     * {@code GET /api/v1/admin/stats/active-users}
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code dimension} (required) — one of {@code DAILY}/{@code WEEKLY}
     *       /{@code MONTHLY}/{@code YEARLY}; case-sensitive (R17.1)</li>
     *   <li>{@code from} (optional ISO date) — inclusive lower bound</li>
     *   <li>{@code to} (optional ISO date) — inclusive upper bound</li>
     * </ul>
     *
     * <p>If both {@code from} and {@code to} are omitted, the dimension's
     * default window applies (R6.5). Bucket count is capped at 366 (R6.4).
     */
    @GetMapping("/active-users")
    public ResponseEntity<ApiResponse<List<ActiveUserBucket>>> activeUsers(
            @RequestParam(value = "dimension", required = false) String dimension,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            HttpServletRequest req) {

        long startNs = System.nanoTime();
        int responseStatus = 200;
        String queryHash = hashQuery(dimension, from, to);
        try {
            List<ActiveUserBucket> data = adminMetrics.uncachedLatency(ENDPOINT_KEY_ACTIVE_USERS)
                    .recordCallable(() -> adminStatsService.aggregateActiveUsers(dimension, from, to));
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException biz) {
            responseStatus = biz.getCode();
            throw biz;
        } catch (RuntimeException rex) {
            responseStatus = 500;
            throw rex;
        } catch (Exception e) {
            // recordCallable forces a checked-exception declaration; the only
            // realistic source is the Feign / cache wrapper which we've already
            // converted to BizException / RuntimeException upstream. Wrap as a
            // last-resort RuntimeException so the global handler emits 500.
            responseStatus = 500;
            throw new RuntimeException(e);
        } finally {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            String adminUserId = req.getHeader(USER_ID_HEADER);
            try {
                AdminAuditLogger.logAccess(
                        adminUserId,
                        ENDPOINT_PATH_ACTIVE_USERS,
                        queryHash,
                        responseStatus,
                        latencyMs);
            } catch (Exception auditEx) {
                // Defensive: audit logging must never break the response path.
                log.debug("admin-audit logging failed: {}", auditEx.getClass().getSimpleName());
            }
        }
    }

    /**
     * Build the {@link QueryHasher}-canonical hash for the active-users query
     * map. Keys must match the design's {@code Endpoint → Hash 输入字段} table
     * exactly: {@code dimension}, {@code from}, {@code to}. {@code null} values
     * are normalised to empty strings so the hash is stable across "absent"
     * vs "explicitly-blank" inputs.
     */
    private static String hashQuery(String dimension, String from, String to) {
        Map<String, String> params = new HashMap<>();
        params.put("dimension", dimension == null ? "" : dimension);
        params.put("from", from == null ? "" : from);
        params.put("to", to == null ? "" : to);
        return QueryHasher.hash(params);
    }
}
