package com.mnemoscape.gateway.admin;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.gateway.filter.RequestCorrelationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregator behind {@code GET /api/v1/admin/health} (Requirement 18.4).
 *
 * <p>Probes every downstream service's {@code /actuator/health} endpoint
 * plus a Redis {@code PING} <em>in parallel</em> and produces a single
 * {@code {overall, components}} envelope as specified in
 * {@code design.md §"GET /api/v1/admin/health"}.
 *
 * <h3>Per-component status mapping</h3>
 * <ul>
 *   <li>actuator returns {@code {"status":"UP"}} → {@link Status#UP}.</li>
 *   <li>actuator returns 5xx in &lt; 1s, or actuator body says
 *       {@code OUT_OF_SERVICE} → {@link Status#DEGRADED}.</li>
 *   <li>no response within 2s, connection refused, or any other
 *       non-classifiable failure → {@link Status#DOWN}.</li>
 * </ul>
 *
 * <h3>Overall computation</h3>
 * <ul>
 *   <li>{@link Status#DOWN} iff any of the three <em>critical</em>
 *       components ({@code auth-service}, {@code memory-service},
 *       {@code redis}) is {@link Status#DOWN}. These three are critical
 *       because R18.4 ties dashboard usability directly to the user
 *       identity / memory data plane / cache layer.</li>
 *   <li>Otherwise {@link Status#DEGRADED} if any component is
 *       {@link Status#DEGRADED}, or any non-critical component
 *       ({@code resonance-service}, {@code asset-service},
 *       {@code ai-service}) is {@link Status#DOWN}.</li>
 *   <li>Otherwise {@link Status#UP}.</li>
 * </ul>
 *
 * <h3>Routing</h3>
 * <p>This handler is bound to the internal forward target
 * {@code /__internal/admin/health} (see
 * {@link com.mnemoscape.gateway.admin.AdminHealthRouter}) and exposed
 * publicly at {@code /api/v1/admin/health} via a {@code forward://} route
 * in {@code application.yml}. That layout means inbound requests still
 * traverse the gateway's global filters — including
 * {@link com.mnemoscape.gateway.filter.AdminGuardFilter} — so only callers
 * with {@code X-User-Role=ADMIN} can reach the aggregator.
 */
@Component
public class HealthHandler {

    private static final Logger log = LoggerFactory.getLogger(HealthHandler.class);

    /** Total per-probe budget. Anything slower → {@link Status#DOWN}. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    /** Latency threshold under which a 5xx is treated as DEGRADED (R18.4). */
    static final Duration DEGRADED_LATENCY_BUDGET = Duration.ofSeconds(1);

    /** Logical service ids — used as the keys of the {@code components} map. */
    static final String AUTH = "auth-service";
    static final String MEMORY = "memory-service";
    static final String RESONANCE = "resonance-service";
    static final String ASSET = "asset-service";
    static final String AI = "ai-service";
    static final String REDIS = "redis";

    /**
     * Components whose DOWN state forces {@code overall=DOWN}. Order is
     * irrelevant for correctness but kept stable for log readability.
     */
    private static final List<String> CRITICAL = List.of(AUTH, MEMORY, REDIS);

    private final WebClient webClient;
    private final ReactiveStringRedisTemplate redis;

    public HealthHandler(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
                         ReactiveStringRedisTemplate redis) {
        // Build the load-balanced client lazily so the @LoadBalanced filter
        // is wired in before any probe runs.
        this.webClient = webClientBuilder.build();
        this.redis = redis;
    }

    /**
     * Reactive {@code RouterFunction} handler invoked by
     * {@link AdminHealthRouter}. Always succeeds with HTTP 200 and the
     * {@link ApiResponse} envelope; per-component DOWN/DEGRADED states are
     * conveyed in the body so the dashboard can render the panel even
     * when the platform is degraded.
     */
    public Mono<ServerResponse> handle(ServerRequest request) {
        String requestId = request.exchange().getAttributeOrDefault(
                RequestCorrelationFilter.CORRELATION_ID_ATTR, "unknown").toString();

        // Probe everything in parallel. Mono.zip waits for the slowest probe,
        // but each probe is bounded by PROBE_TIMEOUT so the overall latency
        // is bounded by ~2s + scheduling.
        Mono<ComponentStatus> authProbe = probeService(AUTH);
        Mono<ComponentStatus> memoryProbe = probeService(MEMORY);
        Mono<ComponentStatus> resonanceProbe = probeService(RESONANCE);
        Mono<ComponentStatus> assetProbe = probeService(ASSET);
        Mono<ComponentStatus> aiProbe = probeService(AI);
        Mono<ComponentStatus> redisProbe = probeRedis();

        return Mono.zip(authProbe, memoryProbe, resonanceProbe, assetProbe, aiProbe, redisProbe)
                .map(t -> assemble(t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5(), t.getT6()))
                .flatMap(payload -> {
                    ApiResponse<Map<String, Object>> envelope =
                            ApiResponse.<Map<String, Object>>builder()
                                    .code(200)
                                    .message("OK")
                                    .data(payload)
                                    .requestId(requestId)
                                    .build();
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(envelope);
                });
    }

    /**
     * Issue {@code GET http://{service}/actuator/health} via the
     * load-balanced WebClient and translate the outcome into a
     * {@link ComponentStatus}.
     *
     * <p>The latency we report is the wall-clock time the probe spent
     * <em>before</em> reaching its terminal signal (response, error or
     * timeout) — that is what the dashboard plots, and it is what
     * differentiates the &lt;1s 5xx (DEGRADED) from the timeout (DOWN).
     */
    private Mono<ComponentStatus> probeService(String serviceName) {
        long start = System.nanoTime();
        return webClient.get()
                .uri("http://" + serviceName + "/actuator/health")
                .retrieve()
                .bodyToMono(Map.class)
                // PROBE_TIMEOUT is the hard ceiling. On expiry Reactor signals
                // TimeoutException; we map that to DOWN with reason "timeout".
                .timeout(PROBE_TIMEOUT)
                .map(body -> classifyActuatorBody(serviceName, body, elapsedMs(start)))
                .onErrorResume(err -> Mono.just(classifyError(serviceName, err, elapsedMs(start))));
    }

    /**
     * Issue a Redis {@code PING} via the reactive lettuce driver and bound
     * the call to {@link #PROBE_TIMEOUT}. A successful PONG ⇒ UP; anything
     * else (timeout, connection refused, RedisConnectionException) ⇒ DOWN.
     * Redis has no actuator-style "OUT_OF_SERVICE" so we never return
     * DEGRADED for the cache layer — the cache is binary.
     */
    private Mono<ComponentStatus> probeRedis() {
        long start = System.nanoTime();
        return redis.getConnectionFactory().getReactiveConnection().ping()
                .timeout(PROBE_TIMEOUT)
                .map(pong -> new ComponentStatus(REDIS, Status.UP, elapsedMs(start), null))
                .onErrorResume(err -> Mono.just(classifyError(REDIS, err, elapsedMs(start))));
    }

    /**
     * Classify a successful HTTP response from {@code /actuator/health}.
     * Spring Boot returns {@code {"status":"UP"}} on health, and
     * {@code "OUT_OF_SERVICE"} or {@code "DOWN"} during graceful shutdown
     * or a registered indicator failure. We treat the former as DEGRADED
     * (the service is reachable but signalling unhealth) and DOWN as DOWN.
     */
    private static ComponentStatus classifyActuatorBody(String name, Map<?, ?> body, long latencyMs) {
        Object raw = body == null ? null : body.get("status");
        String status = raw == null ? "" : raw.toString();
        return switch (status) {
            case "UP" -> new ComponentStatus(name, Status.UP, latencyMs, null);
            case "OUT_OF_SERVICE" ->
                    new ComponentStatus(name, Status.DEGRADED, latencyMs, "actuator OUT_OF_SERVICE");
            case "DOWN" ->
                    new ComponentStatus(name, Status.DOWN, latencyMs, "actuator DOWN");
            default ->
                    // Unknown body shape: treat as DEGRADED rather than DOWN —
                    // we did get a 2xx, so the service is up enough to talk
                    // to us; we just don't recognise its self-report.
                    new ComponentStatus(name, Status.DEGRADED, latencyMs,
                            "actuator unknown status: " + status);
        };
    }

    /**
     * Convert a probe error into a {@link ComponentStatus}. The two
     * distinctions that matter per design:
     * <ol>
     *   <li>5xx response received in &lt;1s → DEGRADED ("actuator 5xx").
     *       Spring's {@link WebClientResponseException} carries the status.</li>
     *   <li>Timeout (Reactor {@code TimeoutException}) → DOWN ("timeout").</li>
     * </ol>
     * Everything else (connect refused, DNS failure, malformed body, …) is
     * also DOWN, which is the conservative choice when we have no
     * positive signal that the service is alive.
     */
    private static ComponentStatus classifyError(String name, Throwable err, long latencyMs) {
        if (err instanceof WebClientResponseException wcre) {
            int httpStatus = wcre.getStatusCode().value();
            if (httpStatus >= 500 && httpStatus < 600) {
                if (latencyMs < DEGRADED_LATENCY_BUDGET.toMillis()) {
                    return new ComponentStatus(name, Status.DEGRADED, latencyMs,
                            "actuator " + httpStatus);
                }
                return new ComponentStatus(name, Status.DOWN, latencyMs,
                        "actuator " + httpStatus + " (slow)");
            }
            // 4xx is not really an availability signal but it indicates the
            // actuator endpoint is mis-configured / secured — DEGRADED.
            return new ComponentStatus(name, Status.DEGRADED, latencyMs,
                    "actuator " + httpStatus);
        }
        if (err instanceof java.util.concurrent.TimeoutException) {
            return new ComponentStatus(name, Status.DOWN, latencyMs, "timeout");
        }
        // Surface the underlying error class so operators can grep the audit
        // log; the message is intentionally short to avoid leaking internals.
        log.debug("Health probe failed name={} err={}", name, err.toString());
        return new ComponentStatus(name, Status.DOWN, latencyMs,
                err.getClass().getSimpleName());
    }

    /**
     * Build the {@code {overall, components}} response body. Order of
     * insertion into the {@code components} map matches the design example
     * (auth → memory → resonance → asset → ai → redis) so the JSON output
     * is stable and readable.
     */
    private static Map<String, Object> assemble(ComponentStatus auth, ComponentStatus memory,
                                                ComponentStatus resonance, ComponentStatus asset,
                                                ComponentStatus ai, ComponentStatus redis) {
        Map<String, ComponentStatus> components = new LinkedHashMap<>();
        components.put(AUTH, auth);
        components.put(MEMORY, memory);
        components.put(RESONANCE, resonance);
        components.put(ASSET, asset);
        components.put(AI, ai);
        components.put(REDIS, redis);

        Status overall = computeOverall(components);

        Map<String, Object> componentsJson = new LinkedHashMap<>();
        for (Map.Entry<String, ComponentStatus> e : components.entrySet()) {
            componentsJson.put(e.getKey(), e.getValue().toMap());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overall", overall.name());
        body.put("components", componentsJson);
        return body;
    }

    /**
     * Apply the overall-status rules from design.md:
     * <pre>
     *   DOWN     iff any critical (auth/memory/redis) is DOWN
     *   DEGRADED iff any component is DEGRADED, or any non-critical is DOWN
     *   UP       otherwise
     * </pre>
     */
    static Status computeOverall(Map<String, ComponentStatus> components) {
        boolean anyDegraded = false;
        boolean anyNonCriticalDown = false;
        for (Map.Entry<String, ComponentStatus> e : components.entrySet()) {
            ComponentStatus c = e.getValue();
            if (c.status == Status.DOWN) {
                if (CRITICAL.contains(e.getKey())) {
                    return Status.DOWN;
                }
                anyNonCriticalDown = true;
            } else if (c.status == Status.DEGRADED) {
                anyDegraded = true;
            }
        }
        if (anyDegraded || anyNonCriticalDown) {
            return Status.DEGRADED;
        }
        return Status.UP;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * Per-component health record. The tuple-style result returned by the
     * parallel probes is converted to {@link #toMap()} only at the end so
     * the assembly step can read {@link #status} without parsing JSON.
     */
    static final class ComponentStatus {
        final String name;
        final Status status;
        final long latencyMs;
        final String reason;

        ComponentStatus(String name, Status status, long latencyMs, String reason) {
            this.name = name;
            this.status = status;
            this.latencyMs = latencyMs;
            this.reason = reason;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status.name());
            m.put("latencyMs", latencyMs);
            // Only emit "reason" when we actually have one — UP probes omit
            // the field entirely (matches the design example, where the
            // healthy components carry only status + latencyMs).
            if (reason != null && !reason.isBlank()) {
                m.put("reason", reason);
            }
            return m;
        }
    }

    enum Status { UP, DEGRADED, DOWN }

    // Suppress unused-import noise for Tuple2 — it is part of the inferred
    // generic in the Mono.zip pipeline above and the explicit reference
    // makes the source self-documenting for readers tracing the types.
    @SuppressWarnings("unused")
    private static final Class<?> TUPLE_REFERENCE_FOR_DOCS = Tuple2.class;
}
