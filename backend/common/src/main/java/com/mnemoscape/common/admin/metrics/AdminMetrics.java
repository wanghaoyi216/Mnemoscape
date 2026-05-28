package com.mnemoscape.common.admin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Micrometer decorator exposing the admin-dashboard observability counters
 * and timers (design §Observability, Requirements 14.5 / 18.3 / 3.5).
 *
 * <p>Every meter name is namespaced under {@code mnemoscape.admin.*} and tagged
 * exactly as specified in the design document's metric naming table:
 *
 * <table border="1">
 *   <tr><th>Meter</th><th>Tags</th><th>Source</th><th>Requirement</th></tr>
 *   <tr>
 *     <td>{@code mnemoscape.admin.cache.hits} (Counter)</td>
 *     <td>{@code endpoint}</td>
 *     <td>{@code BypassCache.get()} hit branch</td>
 *     <td>R14.5</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mnemoscape.admin.cache.misses} (Counter)</td>
 *     <td>{@code endpoint}</td>
 *     <td>{@code BypassCache.get()} miss branch</td>
 *     <td>R14.5</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mnemoscape.admin.uncached.latency} (Timer)</td>
 *     <td>{@code endpoint}</td>
 *     <td>service-method around-aspect / explicit timing</td>
 *     <td>R14.5</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mnemoscape.admin.degraded} (Counter)</td>
 *     <td>{@code endpoint}, {@code reason}</td>
 *     <td>any Feign failure populating {@code degradedReasons}</td>
 *     <td>R18.3</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mnemoscape.admin.authz.rejects} (Counter)</td>
 *     <td>{@code path}, {@code decision}</td>
 *     <td>gateway {@code AdminGuardFilter}</td>
 *     <td>R3.5</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mnemoscape.admin.bootstrap.attempts} (Counter)</td>
 *     <td>{@code result}</td>
 *     <td>{@code AdminBootstrapService}</td>
 *     <td>R1 (operability)</td>
 *   </tr>
 * </table>
 *
 * <p>Each accessor returns a freshly resolved meter from the registry on every
 * call; Micrometer's registry deduplicates by name + tag set, so callers can
 * safely invoke these methods inside hot paths without leaking meters or
 * paying allocation cost beyond the registry's internal lookup.
 *
 * <p>The decorator is a {@link Component} so any service that scans
 * {@code com.mnemoscape.common} (every backend service does) can simply inject
 * it.  Services without a {@link MeterRegistry} bean on the classpath will
 * never instantiate this component — that is intentional, because the metrics
 * surface is meaningful only when actuator / Prometheus is wired.
 */
@Component
public class AdminMetrics {

    /** Common prefix for every meter exposed by this decorator. */
    public static final String PREFIX = "mnemoscape.admin";

    /** Counter name: cache hits per admin endpoint. */
    public static final String METRIC_CACHE_HITS = PREFIX + ".cache.hits";
    /** Counter name: cache misses per admin endpoint. */
    public static final String METRIC_CACHE_MISSES = PREFIX + ".cache.misses";
    /** Timer name: uncached aggregation latency per admin endpoint. */
    public static final String METRIC_UNCACHED_LATENCY = PREFIX + ".uncached.latency";
    /** Counter name: degraded responses per admin endpoint. */
    public static final String METRIC_DEGRADED = PREFIX + ".degraded";
    /** Counter name: gateway authz rejections for admin paths. */
    public static final String METRIC_AUTHZ_REJECTS = PREFIX + ".authz.rejects";
    /** Counter name: bootstrap (role-promotion) attempts. */
    public static final String METRIC_BOOTSTRAP_ATTEMPTS = PREFIX + ".bootstrap.attempts";

    private final MeterRegistry registry;

    @Autowired
    public AdminMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");
    }

    /**
     * Counter incremented on every {@code BypassCache.get()} hit (R14.5).
     *
     * @param endpointKey short endpoint key (e.g. {@code "active-users"},
     *                    {@code "heatmap"}); never the full request path because
     *                    high-cardinality tags blow up Prometheus storage.
     */
    public Counter cacheHits(String endpointKey) {
        return Counter.builder(METRIC_CACHE_HITS)
                .description("Admin aggregation cache hits per endpoint")
                .tag("endpoint", safe(endpointKey))
                .register(registry);
    }

    /**
     * Counter incremented on every {@code BypassCache.get()} miss (R14.5).
     *
     * @param endpointKey short endpoint key (see {@link #cacheHits(String)})
     */
    public Counter cacheMisses(String endpointKey) {
        return Counter.builder(METRIC_CACHE_MISSES)
                .description("Admin aggregation cache misses per endpoint")
                .tag("endpoint", safe(endpointKey))
                .register(registry);
    }

    /**
     * Timer recording wall-clock time spent computing an uncached response
     * (R14.5).  Records seconds with Micrometer's default histogram buckets.
     *
     * @param endpointKey short endpoint key (see {@link #cacheHits(String)})
     */
    public Timer uncachedLatency(String endpointKey) {
        return Timer.builder(METRIC_UNCACHED_LATENCY)
                .description("Admin aggregation uncached query latency per endpoint")
                .tag("endpoint", safe(endpointKey))
                .register(registry);
    }

    /**
     * Counter incremented whenever an admin endpoint returns a degraded
     * response (R18.3).
     *
     * @param endpointKey short endpoint key (see {@link #cacheHits(String)})
     * @param reason      the {@code degradedReasons[i]} value or downstream
     *                    service name; bucketed to a low-cardinality string
     *                    by the caller (avoid raw exception messages).
     */
    public Counter degradedResponses(String endpointKey, String reason) {
        return Counter.builder(METRIC_DEGRADED)
                .description("Admin aggregation degraded responses")
                .tag("endpoint", safe(endpointKey))
                .tag("reason", safe(reason))
                .register(registry);
    }

    /**
     * Counter incremented by the gateway's {@code AdminGuardFilter} on every
     * authorization rejection on an {@code /api/v1/admin/**} path (R3.5).
     *
     * @param pathPrefix logical path prefix (typically the constant
     *                   {@code "/api/v1/admin"} — keep cardinality low)
     * @param decision   {@code "401"} (unauthenticated) or {@code "403"}
     *                   (authenticated but not ADMIN)
     */
    public Counter authzRejects(String pathPrefix, String decision) {
        return Counter.builder(METRIC_AUTHZ_REJECTS)
                .description("Admin path authorization rejections at the gateway")
                .tag("path", safe(pathPrefix))
                .tag("decision", safe(decision))
                .register(registry);
    }

    /**
     * Counter incremented by {@code AdminBootstrapService} on every
     * role-promotion attempt, tagged by outcome.
     *
     * @param result one of {@code success}, {@code already-admin},
     *               {@code secret-mismatch}, {@code disabled},
     *               {@code missing-secret}, {@code not-found}.
     */
    public Counter bootstrapAttempts(String result) {
        return Counter.builder(METRIC_BOOTSTRAP_ATTEMPTS)
                .description("Admin role-promotion (bootstrap) attempts by result")
                .tag("result", safe(result))
                .register(registry);
    }

    /**
     * Replace null tag values with {@code "unknown"} so a faulty caller never
     * registers a meter with an undefined tag (Micrometer rejects null tag
     * values with an exception, which would propagate into the request path).
     */
    private static String safe(String tag) {
        return (tag == null || tag.isEmpty()) ? "unknown" : tag;
    }
}
