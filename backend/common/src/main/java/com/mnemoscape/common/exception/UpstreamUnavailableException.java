package com.mnemoscape.common.exception;

/**
 * Signals that a strictly-required upstream microservice did not respond in
 * time or returned an error that prevents the current request from being
 * fulfilled with live data.
 *
 * <p>Per admin-dashboard Requirements 6.7 and design.md §"R6.7 要求 ...", any
 * admin aggregation endpoint whose primary data source is unreachable must
 * surface HTTP 502 with message {@code "UPSTREAM_UNAVAILABLE"} and SHALL NOT
 * return partial or stale results.
 *
 * <p><b>Diagnostic enrichment (v7)</b>: the original v3 design only carried
 * {@code message="UPSTREAM_UNAVAILABLE"} which made the dashboard error card
 * a black box — operators couldn't tell whether the failure was a 5s connect
 * timeout, a 5xx from the downstream, a missing Nacos registration, or a
 * malformed envelope. We now also carry a structured {@link FailureKind} +
 * a free-form {@code detail} so {@code GlobalExceptionHandler} can surface
 * the root cause to ADMIN callers via the {@code data} field of the error
 * envelope, while keeping the {@code message} stable for backward
 * compatibility with existing client error-code translations.
 *
 * <pre>
 *   HTTP/1.1 502 Bad Gateway
 *   {
 *     "code": 502,
 *     "message": "UPSTREAM_UNAVAILABLE",
 *     "data": {
 *       "upstreamName": "memory-service",
 *       "failureKind": "READ_TIMEOUT",
 *       "detail": "5s read timeout on /admin/stats/active-user-counts"
 *     },
 *     "requestId": "..."
 *   }
 * </pre>
 *
 * <p>The exception is intentionally placed under {@code common} so that
 * auth-service / memory-service / resonance-service can all use the same
 * type when describing "critical dependency unavailable" failure modes.
 */
public class UpstreamUnavailableException extends BizException {

    /** Wire-level error code emitted on the {@code message} field. */
    public static final String ERROR_CODE = "UPSTREAM_UNAVAILABLE";

    /**
     * Coarse-grained failure taxonomy for diagnostics. Distinguishing these
     * cases lets the dashboard show actionable next-steps (e.g. "service
     * not registered in Nacos" vs "downstream returned 5xx") instead of a
     * single opaque banner.
     */
    public enum FailureKind {
        /** TCP connect did not complete within the configured timeout. */
        CONNECT_TIMEOUT,
        /** TCP connect succeeded but no response within the read timeout. */
        READ_TIMEOUT,
        /** Service discovery couldn't resolve the logical name (Nacos / LB layer). */
        NOT_REGISTERED,
        /** Downstream returned an HTTP 5xx (server-side failure). */
        HTTP_5XX,
        /** Downstream returned an HTTP 4xx (client-contract violation). */
        HTTP_4XX,
        /** Downstream returned 200 but the envelope was malformed / empty. */
        MALFORMED_ENVELOPE,
        /** Anything that doesn't match the above (e.g. unexpected RuntimeException). */
        UNKNOWN
    }

    /** Identifier of the failing downstream (e.g. {@code memory-service}). */
    private final String upstreamName;

    /** Coarse-grained failure kind; never {@code null}. */
    private final FailureKind failureKind;

    /** Human-readable diagnostic detail; safe to expose to ADMIN callers. */
    private final String detail;

    /** Backward-compatible constructor: {@link FailureKind#UNKNOWN} + null detail. */
    public UpstreamUnavailableException(String upstreamName) {
        this(upstreamName, FailureKind.UNKNOWN, null, null);
    }

    /** Backward-compatible constructor with cause but no kind. */
    public UpstreamUnavailableException(String upstreamName, Throwable cause) {
        this(upstreamName, FailureKind.UNKNOWN, null, cause);
    }

    /**
     * Full constructor — preferred at every call site that knows the failure
     * kind.
     *
     * @param upstreamName logical service name of the failing upstream;
     *                     {@code null} → {@code "unknown"}
     * @param failureKind  coarse-grained failure kind; {@code null} →
     *                     {@link FailureKind#UNKNOWN}
     * @param detail       human-readable diagnostic detail; may be
     *                     {@code null}
     * @param cause        the originating exception or {@code null}
     */
    public UpstreamUnavailableException(String upstreamName, FailureKind failureKind,
                                         String detail, Throwable cause) {
        super(502, ERROR_CODE);
        this.upstreamName = upstreamName == null ? "unknown" : upstreamName;
        this.failureKind = failureKind == null ? FailureKind.UNKNOWN : failureKind;
        this.detail = detail;
        if (cause != null) {
            initCause(cause);
        }
    }

    /** @return the logical service name that failed (for log / metric tags). */
    public String getUpstreamName() {
        return upstreamName;
    }

    /** @return coarse-grained failure kind, never {@code null}. */
    public FailureKind getFailureKind() {
        return failureKind;
    }

    /** @return human-readable diagnostic detail, may be {@code null}. */
    public String getDetail() {
        return detail;
    }
}
