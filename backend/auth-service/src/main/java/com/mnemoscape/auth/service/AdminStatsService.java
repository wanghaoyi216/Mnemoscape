package com.mnemoscape.auth.service;

import com.mnemoscape.auth.admin.config.AdminCacheConfig;
import com.mnemoscape.auth.client.MemoryServiceClient;
import com.mnemoscape.auth.model.dto.ActiveUserBucket;
import com.mnemoscape.common.admin.codec.IsoDateCodec;
import com.mnemoscape.common.admin.codec.TimeDimensionCodec;
import com.mnemoscape.common.admin.codec.TimeDimensionCodec.Dimension;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.exception.UpstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Aggregation service for the auth-service-side admin endpoints
 * (admin-dashboard task 6.6).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate {@code dimension} (R6.3) and date range (R6.4 — at most 366
 *       buckets, {@code from <= to}).</li>
 *   <li>Apply the dimension-specific default window when {@code from} or
 *       {@code to} is omitted (R6.5).</li>
 *   <li>Delegate the actual time-bucketed count to memory-service via Feign
 *       (R6.6) and surface failures as HTTP 502 {@code UPSTREAM_UNAVAILABLE}
 *       (R6.7) — active-users is a "critical dependency" path so partial /
 *       stale data is explicitly forbidden.</li>
 *   <li>Memoize successful responses for 60s in the {@code admin.active-users}
 *       Redis cache (R14.1 / R14.2).</li>
 * </ul>
 *
 * <p>Default windows (R6.5) — both {@code from} and {@code to} omitted:
 * <ul>
 *   <li>{@code DAILY}    → 30 days</li>
 *   <li>{@code WEEKLY}   → 12 weeks</li>
 *   <li>{@code MONTHLY}  → 12 months</li>
 *   <li>{@code YEARLY}   → 5 years</li>
 * </ul>
 *
 * <p>If only one of the two range parameters is supplied, the other defaults
 * to "today" (UTC) for {@code to} or to {@link #defaultFromFor} for
 * {@code from}. The combination is then validated by the upstream contract
 * (memory-service rejects {@code from > to} with 400 INVALID_RANGE).
 */
@Service
public class AdminStatsService {

    private static final Logger log = LoggerFactory.getLogger(AdminStatsService.class);

    /** Hard upper bound on bucket count per request — mirrors the memory-service constant. */
    private static final long MAX_BUCKET_COUNT = 366L;

    private final MemoryServiceClient memoryServiceClient;

    public AdminStatsService(MemoryServiceClient memoryServiceClient) {
        this.memoryServiceClient = memoryServiceClient;
    }

    /**
     * Aggregate the active-user counts for the supplied dimension and
     * (optional) range. The computation is delegated to memory-service via
     * Feign — the auth-service owns this endpoint only to keep the public
     * surface clean for the dashboard.
     *
     * @param dimensionRaw raw dimension token from the query string (must be
     *                     one of {@code DAILY}/{@code WEEKLY}/{@code MONTHLY}/{@code YEARLY});
     *                     null/empty/lowercase → 400 {@code INVALID_DIMENSION}
     * @param fromRaw      raw {@code from} ISO date or {@code null}
     * @param toRaw        raw {@code to} ISO date or {@code null}
     * @return the zero-filled bucket series
     * @throws BizException 400 {@code INVALID_DIMENSION} / {@code INVALID_RANGE}
     *                      / 502 {@code UPSTREAM_UNAVAILABLE}
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_ACTIVE_USERS,
            key = "T(com.mnemoscape.auth.service.AdminStatsService).cacheKey(#dimensionRaw, #fromRaw, #toRaw)",
            sync = true)
    public List<ActiveUserBucket> aggregateActiveUsers(String dimensionRaw, String fromRaw, String toRaw) {
        Dimension dimension = parseDimension(dimensionRaw);

        LocalDate to = parseToOrToday(toRaw);
        LocalDate from = parseFromOrDefault(fromRaw, dimension, to);

        if (from.isAfter(to)) {
            throw new BizException(400, "INVALID_RANGE");
        }
        if (estimateBucketCount(dimension, from, to) > MAX_BUCKET_COUNT) {
            throw new BizException(400, "INVALID_RANGE");
        }

        ApiResponse<List<ActiveUserBucket>> resp;
        try {
            resp = memoryServiceClient.activeUserCounts(
                    dimension,
                    IsoDateCodec.print(from),
                    IsoDateCodec.print(to));
        } catch (UpstreamUnavailableException e) {
            // The Feign error decoder already translated to this canonical type.
            throw e;
        } catch (BizException biz) {
            // Pass-through any structured rejection from the upstream service.
            throw biz;
        } catch (feign.RetryableException retry) {
            // Connect / read timeouts arrive here (before our ErrorDecoder runs).
            // Distinguish CONNECT_TIMEOUT vs READ_TIMEOUT by inspecting the cause —
            // ConnectException happens during the TCP handshake; SocketTimeoutException
            // post-connect signals a read timeout.
            UpstreamUnavailableException.FailureKind kind = retry.getCause() instanceof java.net.ConnectException
                    ? UpstreamUnavailableException.FailureKind.CONNECT_TIMEOUT
                    : UpstreamUnavailableException.FailureKind.READ_TIMEOUT;
            String detail = kind.name().toLowerCase().replace('_', ' ')
                    + " talking to memory-service (5s budget exceeded)";
            log.warn("memory-service feign retryable kind={} causeType={} message={}",
                    kind, retry.getCause() == null ? "none" : retry.getCause().getClass().getSimpleName(),
                    retry.getMessage());
            throw new UpstreamUnavailableException("memory-service", kind, detail, retry);
        } catch (Exception e) {
            // Service-discovery failures (Nacos can't resolve "memory-service") and
            // everything else not covered above. We try to detect the Spring Cloud
            // LoadBalancer "no instances available" message because that's the most
            // actionable: it means the downstream isn't registered or all instances
            // are marked DOWN.
            String message = e.getMessage() == null ? "" : e.getMessage();
            UpstreamUnavailableException.FailureKind kind;
            String detail;
            if (message.contains("No instances available") || message.contains("Load balancer")
                    || e.getClass().getSimpleName().contains("Resolution")) {
                kind = UpstreamUnavailableException.FailureKind.NOT_REGISTERED;
                detail = "memory-service is not registered in Nacos or all instances are DOWN";
            } else {
                kind = UpstreamUnavailableException.FailureKind.UNKNOWN;
                detail = e.getClass().getSimpleName() + ": " + message;
            }
            log.warn("memory-service feign call failed type={} kind={} message={}",
                    e.getClass().getSimpleName(), kind, message);
            throw new UpstreamUnavailableException("memory-service", kind, detail, e);
        }

        if (resp == null || resp.getData() == null) {
            // Defensive: the upstream contract is "always returns a list"; absence
            // signals a malformed envelope which we treat the same as a Feign error.
            throw new UpstreamUnavailableException("memory-service",
                    UpstreamUnavailableException.FailureKind.MALFORMED_ENVELOPE,
                    "memory-service returned null envelope or null data field",
                    null);
        }
        if (resp.getCode() != 200) {
            // Upstream error envelope (rare since the ErrorDecoder catches HTTP
            // status; this path covers a custom 200-with-non-OK code shape).
            throw new BizException(resp.getCode(),
                    resp.getMessage() == null ? "UPSTREAM_UNAVAILABLE" : resp.getMessage());
        }
        return Collections.unmodifiableList(resp.getData());
    }

    /**
     * Compose the SpEL-friendly cache key used by {@link #aggregateActiveUsers}.
     * Public + static so Spring's {@code @Cacheable} key expression can resolve it.
     */
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
            return LocalDate.now(java.time.ZoneOffset.UTC);
        }
        return IsoDateCodec.tryParse(toRaw)
                .orElseThrow(() -> new BizException(400, "INVALID_RANGE"));
    }

    private static LocalDate parseFromOrDefault(String fromRaw, Dimension dim, LocalDate to) {
        if (fromRaw == null || fromRaw.isBlank()) {
            return defaultFromFor(dim, to);
        }
        return IsoDateCodec.tryParse(fromRaw)
                .orElseThrow(() -> new BizException(400, "INVALID_RANGE"));
    }

    /**
     * Default window per dimension (R6.5). Mirrors
     * {@code com.mnemoscape.memory.admin.util.TimeBucketing.defaultFrom} so
     * the auth-side and memory-side behaviour agree when one of the range
     * parameters is supplied and the other is not.
     */
    private static LocalDate defaultFromFor(Dimension dim, LocalDate to) {
        return switch (dim) {
            case DAILY   -> to.minusDays(30);
            case WEEKLY  -> to.minusWeeks(12);
            case MONTHLY -> to.minusMonths(12);
            case YEARLY  -> to.minusYears(5);
        };
    }

    /**
     * Conservative upper-bound estimate of bucket count for the supplied range.
     * The memory-service authoritative check uses the precise alignment-aware
     * counter; we duplicate a simpler check here so we can fast-fail before
     * paying the Feign round-trip cost.
     */
    private static long estimateBucketCount(Dimension dim, LocalDate from, LocalDate to) {
        return switch (dim) {
            case DAILY -> java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1L;
            case WEEKLY -> java.time.temporal.ChronoUnit.WEEKS.between(
                    from.with(java.time.DayOfWeek.MONDAY),
                    to.with(java.time.DayOfWeek.MONDAY)) + 1L;
            case MONTHLY -> java.time.temporal.ChronoUnit.MONTHS.between(
                    from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1L;
            case YEARLY -> java.time.temporal.ChronoUnit.YEARS.between(
                    from.withDayOfYear(1), to.withDayOfYear(1)) + 1L;
        };
    }
}
