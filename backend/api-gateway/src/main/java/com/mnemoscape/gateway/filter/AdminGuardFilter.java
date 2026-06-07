package com.mnemoscape.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.dto.ApiResponse;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Gateway authorization filter for the admin dashboard surface
 * (Requirements 3.1, 3.3, 3.4, 3.5).
 *
 * <p>Runs at {@link Ordered#getOrder() order} {@code -90}, i.e. immediately
 * after {@link AuthGlobalFilter} (-100). By that point a valid JWT has been
 * parsed and the gateway has injected {@code X-User-Role} carrying the
 * case-sensitive value {@code "ADMIN"} or {@code "USER"} (or, for public
 * paths, possibly nothing at all). For any path under
 * {@code /api/v1/admin/} this filter enforces:
 *
 * <ul>
 *   <li>missing {@code X-User-Role} → HTTP 401 with {@code AUTH_REQUIRED}
 *       (defensive: this branch is only reachable if upstream filters were
 *       reordered or the path was added to the public list — the regular
 *       JWT-failure path returns 401 from {@code AuthGlobalFilter} first).</li>
 *   <li>{@code X-User-Role != "ADMIN"} → HTTP 403 with {@code ADMIN_REQUIRED}.</li>
 *   <li>{@code X-User-Role == "ADMIN"} → forwarded downstream.</li>
 * </ul>
 *
 * <p>The response body in every reject branch is a JSON-serialised
 * {@link ApiResponse} envelope so the frontend's existing error handling
 * (which already understands the envelope) stays uniform with the rest of
 * the platform (R3.4).
 *
 * <p>Every decision (ALLOW / FORBIDDEN / UNAUTHORIZED) emits a structured
 * record on the dedicated {@code admin-authz} logger (R3.5) carrying
 * {@code userId / userRole / path / method / decision / requestId}, and
 * each rejection additionally bumps the
 * {@code mnemoscape.admin.authz.rejects} counter via {@link AdminMetrics}
 * (tagged {@code path=/api/v1/admin}, {@code decision=401|403}).
 *
 * <p>Implementation note: although the task description calls this a
 * "reactive WebFilter", it implements {@link GlobalFilter} rather than
 * {@code org.springframework.web.server.WebFilter}. WebFilter beans run in
 * the WebFlux {@code WebFilterChainProxy} which executes <em>before</em>
 * Spring Cloud Gateway's {@code FilteringWebHandler}; in that ordering the
 * {@code X-User-Role} header injected by {@code AuthGlobalFilter} would
 * not yet be visible. Implementing {@code GlobalFilter} keeps both filters
 * in the gateway pipeline so the {@code -100 → -90} ordering actually
 * means "AdminGuard runs after AuthGlobal".
 */
@Component
public class AdminGuardFilter implements GlobalFilter, Ordered {

    /** Dedicated authz logger (configured in {@code logback-admin.xml}). */
    private static final Logger AUTHZ = LoggerFactory.getLogger("admin-authz");

    /** Path prefix that triggers admin enforcement (R3.1). */
    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";

    /**
     * Low-cardinality {@code path} tag for the authz reject counter. Using
     * the literal prefix instead of the request URI keeps Prometheus
     * cardinality bounded; per-endpoint detail belongs in the structured
     * log (R3.5 audit trail), not in the counter dimensions.
     */
    private static final String AUTHZ_PATH_TAG = "/api/v1/admin";

    private static final String DECISION_ALLOW = "ALLOW";
    private static final String DECISION_FORBIDDEN = "FORBIDDEN";
    private static final String DECISION_UNAUTHORIZED = "UNAUTHORIZED";

    /** {@code message} value placed in the {@link ApiResponse} 401 body. */
    private static final String CODE_AUTH_REQUIRED = "AUTH_REQUIRED";

    /** {@code message} value placed in the {@link ApiResponse} 403 body. */
    private static final String CODE_ADMIN_REQUIRED = "ADMIN_REQUIRED";

    /** Header values used for redacted log fields when the upstream did not set them. */
    private static final String USER_ID_ABSENT = "anonymous";
    private static final String USER_ROLE_ABSENT = "absent";

    /**
     * Single-instance Jackson mapper used to serialise the small
     * {@link ApiResponse} reject envelopes. Filter-side construction is
     * fine because the mapper is thread-safe and there is no
     * service-specific configuration to honour at the gateway layer.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AdminMetrics adminMetrics;

    public AdminGuardFilter(AdminMetrics adminMetrics) {
        this.adminMetrics = adminMetrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // Non-admin paths pass through untouched. The early-return is the hot
        // path for the gateway as a whole (admin traffic is a tiny fraction
        // of total requests) so it must avoid header lookups and allocations.
        if (!path.startsWith(ADMIN_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = request.getHeaders();
        String userId = headers.getFirst("X-User-Id");
        String role = headers.getFirst("X-User-Role");
        HttpMethod httpMethod = request.getMethod();
        String method = httpMethod != null ? httpMethod.name() : "UNKNOWN";
        String requestId = resolveRequestId(exchange);

        if (role == null || role.isBlank()) {
            // X-User-Role is injected by AuthGlobalFilter on every successful
            // JWT validation; reaching this branch means the request bypassed
            // authentication entirely, which is itself a 401.
            adminMetrics.authzRejects(AUTHZ_PATH_TAG, "401").increment();
            logDecision(DECISION_UNAUTHORIZED, userId, role, path, method, requestId);
            return writeApiResponse(exchange, HttpStatus.UNAUTHORIZED,
                    ApiResponse.unauthorized(CODE_AUTH_REQUIRED), requestId);
        }

        if (!"ADMIN".equals(role)) {
            // R3.1: case-sensitive equality with the literal "ADMIN". Any
            // other value (USER, "Admin", typos) is denied at 403.
            adminMetrics.authzRejects(AUTHZ_PATH_TAG, "403").increment();
            logDecision(DECISION_FORBIDDEN, userId, role, path, method, requestId);
            return writeApiResponse(exchange, HttpStatus.FORBIDDEN,
                    ApiResponse.forbidden(CODE_ADMIN_REQUIRED), requestId);
        }

        // Allow: log the access decision but do not bump the rejects counter.
        logDecision(DECISION_ALLOW, userId, role, path, method, requestId);
        return chain.filter(exchange);
    }

    /**
     * Read the correlation id stamped on the exchange by
     * {@link RequestCorrelationFilter}. Falls back to {@code "unknown"} so
     * the structured log never emits a {@code null} key.
     */
    private static String resolveRequestId(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(RequestCorrelationFilter.CORRELATION_ID_ATTR);
        return value != null ? value.toString() : "unknown";
    }

    /**
     * Emit a single structured {@code admin-authz} record (R3.5).
     *
     * <p>Uses {@link StructuredArguments#kv(String, Object)} so the Logstash
     * encoder writes each pair as a top-level JSON field rather than
     * interpolating it into the message text — that keeps the audit log
     * easy to query in Loki / ELK.
     */
    private static void logDecision(String decision, String userId, String role,
                                    String path, String method, String requestId) {
        AUTHZ.info("admin-authz",
                StructuredArguments.kv("userId", userId == null ? USER_ID_ABSENT : userId),
                StructuredArguments.kv("userRole", (role == null || role.isBlank()) ? USER_ROLE_ABSENT : role),
                StructuredArguments.kv("path", path),
                StructuredArguments.kv("method", method),
                StructuredArguments.kv("decision", decision),
                StructuredArguments.kv("requestId", requestId)
        );
    }

    /**
     * Write the supplied {@link ApiResponse} envelope as the response body
     * with the given HTTP status. Mirrors the response shape used elsewhere
     * (see {@code AuthGlobalFilter#unauthorized}) including the
     * {@code X-Correlation-Id} response header so clients can correlate the
     * 401/403 with their request.
     */
    private Mono<Void> writeApiResponse(ServerWebExchange exchange, HttpStatus status,
                                         ApiResponse<?> body, String requestId) {
        body.setRequestId(requestId);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        HttpHeaders responseHeaders = response.getHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        responseHeaders.set(RequestCorrelationFilter.CORRELATION_ID_HEADER, requestId);

        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // Defensive fallback: ApiResponse is a plain bean so this should
            // never happen, but if Jackson somehow rejects it we still want
            // to return a well-formed JSON envelope rather than a 500.
            payload = ("{\"code\":" + status.value()
                    + ",\"message\":\"" + body.getMessage()
                    + "\",\"data\":null,\"requestId\":\"" + requestId + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(payload);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // -100 = AuthGlobalFilter (validates JWT, injects X-User-Role).
        // -90  = AdminGuardFilter (this), runs immediately afterwards so
        // the header it relies on is guaranteed to be present (or proven
        // absent for the defensive 401 branch).
        return -90;
    }
}
