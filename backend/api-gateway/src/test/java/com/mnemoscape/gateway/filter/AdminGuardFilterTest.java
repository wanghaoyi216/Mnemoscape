package com.mnemoscape.gateway.filter;

import com.mnemoscape.common.admin.metrics.AdminMetrics;
import com.mnemoscape.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AdminGuardFilter} (admin-dashboard task 3.3, validates
 * Requirements 3.1 / 3.3 / 3.4 / 3.5).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Non-admin path passes through untouched.</li>
 *   <li>Admin path with no {@code X-User-Role} → 401 {@code AUTH_REQUIRED}
 *       and bumps the {@code mnemoscape.admin.authz.rejects} counter
 *       tagged {@code decision=401}.</li>
 *   <li>Admin path with {@code X-User-Role=USER} → 403 {@code ADMIN_REQUIRED}
 *       and bumps the rejects counter tagged {@code decision=403}.</li>
 *   <li>Admin path with {@code X-User-Role=ADMIN} → forwarded downstream.</li>
 *   <li>Response envelope contains the correlation id from the request
 *       attribute (R3.4).</li>
 * </ul>
 */
class AdminGuardFilterTest {

    private SimpleMeterRegistry registry;
    private AdminMetrics metrics;
    private AdminGuardFilter filter;
    private GatewayFilterChain chain;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AdminMetrics(registry);
        filter = new AdminGuardFilter(metrics);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchange(String path, String role) {
        MockServerHttpRequest.BaseBuilder<?> builder =
                MockServerHttpRequest.get(path);
        if (role != null) {
            builder.header("X-User-Role", role);
        }
        builder.header("X-User-Id", "u-1");
        MockServerWebExchange ex = MockServerWebExchange.from(builder.build());
        ex.getAttributes().put(RequestCorrelationFilter.CORRELATION_ID_ATTR, "test-corr-id");
        return ex;
    }

    private static long rejectsCount(SimpleMeterRegistry reg, String decision) {
        var c = reg.find("mnemoscape.admin.authz.rejects")
                .tag("path", "/api/v1/admin")
                .tag("decision", decision)
                .counter();
        return c == null ? 0 : (long) c.count();
    }

    private static String responseBody(MockServerWebExchange ex) {
        var dataBuffer = ex.getResponse().getBody();
        return DataBufferUtils.join(dataBuffer)
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                })
                .blockOptional()
                .orElse("");
    }

    @Test
    @DisplayName("non-admin path passes through filter chain")
    void nonAdminPathPassesThrough() {
        MockServerWebExchange ex = exchange("/api/v1/memories", "USER");
        filter.filter(ex, chain).block();
        verify(chain, times(1)).filter(any());
        assertEquals(0L, rejectsCount(registry, "401"));
        assertEquals(0L, rejectsCount(registry, "403"));
    }

    @Test
    @DisplayName("admin path with no X-User-Role → 401 AUTH_REQUIRED")
    void adminPathWithoutRoleReturns401() throws Exception {
        MockServerWebExchange ex = exchange("/api/v1/admin/stats/active-users", null);
        filter.filter(ex, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        verify(chain, never()).filter(any());

        String body = responseBody(ex);
        ApiResponse<?> resp = mapper.readValue(body, ApiResponse.class);
        assertEquals(401, resp.getCode());
        assertEquals("AUTH_REQUIRED", resp.getMessage());
        assertEquals("test-corr-id", resp.getRequestId());

        assertEquals(1L, rejectsCount(registry, "401"));
        // Response also has the correlation header set.
        assertNotNull(ex.getResponse().getHeaders().getFirst(RequestCorrelationFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("admin path with X-User-Role=USER → 403 ADMIN_REQUIRED")
    void adminPathWithUserRoleReturns403() throws Exception {
        MockServerWebExchange ex = exchange("/api/v1/admin/stats/active-users", "USER");
        filter.filter(ex, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, ex.getResponse().getStatusCode());
        verify(chain, never()).filter(any());

        String body = responseBody(ex);
        ApiResponse<?> resp = mapper.readValue(body, ApiResponse.class);
        assertEquals(403, resp.getCode());
        assertEquals("ADMIN_REQUIRED", resp.getMessage());

        assertEquals(1L, rejectsCount(registry, "403"));
    }

    @Test
    @DisplayName("admin path with lowercase 'admin' is also rejected (case-sensitive)")
    void caseSensitiveRoleCheck() {
        MockServerWebExchange ex = exchange("/api/v1/admin/stats/active-users", "admin");
        filter.filter(ex, chain).block();
        assertEquals(HttpStatus.FORBIDDEN, ex.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("admin path with X-User-Role=ADMIN passes through")
    void adminPathWithAdminRolePassesThrough() {
        MockServerWebExchange ex = exchange("/api/v1/admin/stats/active-users", "ADMIN");
        filter.filter(ex, chain).block();
        verify(chain, times(1)).filter(any());
        assertEquals(0L, rejectsCount(registry, "401"));
        assertEquals(0L, rejectsCount(registry, "403"));
        // Response status not set (chain handles it)
        assertEquals(null, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("filter order is -90 (between AuthGlobalFilter -100 and downstream)")
    void filterOrderIsMinus90() {
        assertEquals(-90, filter.getOrder());
    }

    @Test
    @DisplayName("non-admin path with empty role passes through (filter only checks /api/v1/admin/)")
    void nonAdminPathWithEmptyRolePassesThrough() {
        MockServerWebExchange ex = exchange("/api/v1/auth/login", null);
        filter.filter(ex, chain).block();
        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("admin path with blank X-User-Role → 401")
    void adminPathWithBlankRoleReturns401() {
        MockServerWebExchange ex = exchange("/api/v1/admin/stats/active-users", "   ");
        filter.filter(ex, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        assertEquals(1L, rejectsCount(registry, "401"));
    }

    @Test
    @DisplayName("response Content-Type is JSON for both 401 and 403 reject branches")
    void rejectionBodyIsJson() {
        MockServerWebExchange ex401 = exchange("/api/v1/admin/foo", null);
        filter.filter(ex401, chain).block();
        assertTrue(ex401.getResponse().getHeaders().getFirst("Content-Type")
                .startsWith("application/json"));

        MockServerWebExchange ex403 = exchange("/api/v1/admin/foo", "USER");
        filter.filter(ex403, chain).block();
        assertTrue(ex403.getResponse().getHeaders().getFirst("Content-Type")
                .startsWith("application/json"));
    }
}
