package com.mnemoscape.auth.client;

import com.mnemoscape.auth.client.config.MemoryServiceFeignConfig;
import com.mnemoscape.auth.model.dto.ActiveUserBucket;
import com.mnemoscape.common.admin.codec.TimeDimensionCodec.Dimension;
import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Feign client to memory-service for the admin dashboard
 * {@code active-users} aggregation.
 *
 * <p>This client exclusively serves
 * {@code GET /api/v1/admin/stats/active-users} on the auth-service side
 * (admin-dashboard design.md §"#### auth-service · `/admin/**` 端点 → §2"):
 * auth-service owns the public endpoint, but the underlying time-bucketed
 * counts are computed by memory-service against the {@code memories} table.
 *
 * <p>Behavior contract:
 * <ul>
 *   <li><b>Timeout</b>: 5 seconds (connect + read) — enforced by
 *       {@link MemoryServiceFeignConfig#feignRequestOptions()}, matching
 *       Requirements 6.7 ("Memory_Service call fails or does not respond
 *       within 5 seconds → 502 UPSTREAM_UNAVAILABLE").</li>
 *   <li><b>Error mapping</b>: any non-2xx response, network error, or
 *       timeout is translated by
 *       {@link MemoryServiceFeignConfig.MemoryServiceErrorDecoder}
 *       into a
 *       {@link com.mnemoscape.common.exception.UpstreamUnavailableException}
 *       so that {@code GlobalExceptionHandler} surfaces HTTP 502 with
 *       message {@code "UPSTREAM_UNAVAILABLE"} (Requirements 6.7 / 13.2).</li>
 *   <li><b>No fallback</b>: active-users is a "critical dependency" path —
 *       partial / stale data is explicitly forbidden by the requirements;
 *       the dashboard's {@code degraded: true} envelope is reserved for
 *       multi-source endpoints (top-contributors, resonance-overview).</li>
 * </ul>
 *
 * <p>Because Spring's {@code @RequestParam} converters serialize an enum
 * via {@code toString()} (which equals {@code name()} for our
 * {@link Dimension}), passing the enum directly produces the exact wire
 * form expected by the canonical {@code TimeDimensionCodec}
 * (e.g. {@code DAILY} / {@code WEEKLY}). {@code from} / {@code to} are
 * passed as ISO local-date strings ({@code YYYY-MM-DD}) so the auth-side
 * can short-circuit serialisation when the caller omits them — matching
 * the {@code IsoDateCodec} round-trip property.
 */
@FeignClient(
        name = "memory-service",
        contextId = "memoryServiceForAdminStats",
        path = "/api/v1",
        configuration = MemoryServiceFeignConfig.class)
public interface MemoryServiceClient {

    /**
     * Fetch zero-filled active-user bucket counts for the supplied dimension
     * and date range.
     *
     * <p>Wire route (memory-service side):
     * {@code GET /api/v1/admin/stats/active-user-counts}.
     *
     * <p>The {@code from} and {@code to} parameters are nullable; when both
     * are omitted memory-service is required to apply the dimension-specific
     * default window (DAILY 30 days / WEEKLY 12 weeks / MONTHLY 12 months /
     * YEARLY 5 years) per Requirements 6.5.
     *
     * @param dimension the time bucketing dimension
     * @param from      ISO local date ({@code YYYY-MM-DD}) — inclusive lower
     *                  bound; may be {@code null}
     * @param to        ISO local date ({@code YYYY-MM-DD}) — inclusive upper
     *                  bound; may be {@code null}
     * @return {@link ApiResponse} envelope wrapping a strictly ordered, one
     *         entry per bucket list of {@link ActiveUserBucket}
     */
    @GetMapping("/admin/stats/active-user-counts")
    ApiResponse<List<ActiveUserBucket>> activeUserCounts(
            @RequestParam("dimension") Dimension dimension,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to);
}
