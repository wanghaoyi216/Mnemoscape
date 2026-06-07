package com.mnemoscape.common.admin;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated audit logger for the admin dashboard aggregation endpoints
 * (Requirements 15.4).
 *
 * <p>Every admin endpoint MUST call {@link #logAccess(String, String, String, int, long)}
 * once per request after the response status and latency are known. Records are
 * routed to the {@code admin-audit} Logback logger which is wired (per service)
 * to a {@code RollingFileAppender} writing {@code logs/admin-audit.log} via the
 * Logstash JSON encoder. The logger name is hard-coded so that the appender
 * binding does not depend on the calling class's package.
 *
 * <p>Field semantics, matching the design's {@code admin-audit} JSON shape:
 * <ul>
 *   <li>{@code adminUserId} — the {@code X-User-Id} header value injected by the
 *       gateway after JWT validation. Never the raw JWT subject string from
 *       request bodies.</li>
 *   <li>{@code endpoint} — the canonical request path (e.g.
 *       {@code /api/v1/admin/stats/heatmap}); query string MUST be omitted
 *       because parameters can carry user-identifying values.</li>
 *   <li>{@code queryHash} — the SHA-256 hex digest (first 16 chars) produced by
 *       {@link QueryHasher#hash(java.util.Map)}; never the raw query string
 *       (Requirements 15.5).</li>
 *   <li>{@code status} — the HTTP status returned to the caller.</li>
 *   <li>{@code latencyMs} — wall-clock time the controller spent producing the
 *       response, in milliseconds.</li>
 *   <li>{@code ts} — server-side epoch millis, useful for offline correlation
 *       even when the appender's own timestamp is rewritten by an aggregator.</li>
 * </ul>
 *
 * <p>This class is intentionally a static utility: audit logging is a fire-and-forget
 * cross-cutting concern and the SLF4J logger is thread-safe.
 */
public final class AdminAuditLogger {

    /**
     * Dedicated SLF4J logger name. Logback configuration in each service binds
     * this name (with {@code additivity=false}) to the {@code ADMIN_AUDIT}
     * appender so admin access records do not pollute the main application log.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("admin-audit");

    private AdminAuditLogger() {
        // utility class — no instances
    }

    /**
     * Append a single structured admin-access record.
     *
     * <p>The call always succeeds: it never throws, even if any argument is
     * {@code null}, because audit logging must not be able to break the request
     * it is recording.
     *
     * @param adminUserId   the calling admin's user id (gateway-injected); may be
     *                      {@code null} only when authn upstream is misconfigured
     * @param endpoint      canonical path (no query string)
     * @param queryHash     SHA-256-prefix hash from {@link QueryHasher}; pass
     *                      {@code "unhashable"} (the QueryHasher fallback)
     *                      rather than {@code null} when the hash failed
     * @param responseStatus the HTTP status code returned to the caller
     * @param latencyMs     end-to-end controller latency in milliseconds
     */
    public static void logAccess(
            String adminUserId,
            String endpoint,
            String queryHash,
            int responseStatus,
            long latencyMs) {
        AUDIT.info("admin-access",
                StructuredArguments.kv("adminUserId", adminUserId),
                StructuredArguments.kv("endpoint", endpoint),
                StructuredArguments.kv("queryHash", queryHash),
                StructuredArguments.kv("status", responseStatus),
                StructuredArguments.kv("latencyMs", latencyMs),
                StructuredArguments.kv("ts", System.currentTimeMillis())
        );
    }
}
