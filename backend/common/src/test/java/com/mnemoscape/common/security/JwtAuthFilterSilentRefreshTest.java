package com.mnemoscape.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link JwtAuthFilter}'s R14 静默刷新 extension point.
 *
 * <p>集中覆盖以下场景：
 * <ul>
 *   <li>access 过期 + SilentRefreshHandler 返回新 token → 业务链继续，新 token 写入
 *       {@code X-New-Access-Token} / {@code X-New-Refresh-Token} response header，
 *       SecurityContext 被新 claims 重建。</li>
 *   <li>access 过期 + SilentRefreshHandler 返回 empty → 仍然 401，无副作用。</li>
 *   <li>SilentRefreshHandler 未注入 + access 过期 → 仍然 401，无 NPE。</li>
 *   <li>access 没过期 → 不走静默刷新路径，避免误调用。</li>
 * </ul>
 */
class JwtAuthFilterSilentRefreshTest {

    private static final String SECRET =
            "bW5lbW9zY2FwZS1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni1hbGdvcml0aG0tMjAyNQ==";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        // 用一个很短的 access TTL 让"过期"容易构造（10ms）。refresh TTL 给 1 小时。
        provider = new JwtTokenProvider(SECRET, 10L, 3_600_000L);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("access 过期 + refresh 有效 + handler 返回新 token → 业务链继续 + 新 token 写入 response header")
    void expiredAccessTriggersSilentRefresh() throws Exception {
        // 1. 让一个 access token 自然过期
        String expiredAccess = provider.generateAccessToken("u-1", "alice", "USER");
        Thread.sleep(50L);
        // 此时 expiredAccess 已经过期

        // 2. 构造一个 mock SilentRefreshHandler：调一下就返回新一对 token
        SilentRefreshHandler handler = (request, expired) -> Optional.of(
                new SilentRefreshHandler.RefreshedTokens(
                        "u-1", "alice", "USER", "new-access", "new-refresh"));

        JwtAuthFilter filter = new JwtAuthFilter(provider, JwtBlacklist.NOOP, handler);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.addHeader("Authorization", "Bearer " + expiredAccess);
        req.addHeader("X-Refresh-Token", "some-refresh-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        FilterChain chain = (rq, r) -> {
            // 业务链跑通证明 SecurityContext 已经建好
            assertNotNull(SecurityContextHolder.getContext().getAuthentication(),
                    "silent refresh success path must authenticate the request");
            assertEquals("u-1",
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        };

        filter.doFilter(req, resp, chain);

        // 新 token 必须写进 response header，供前端拦截器透明替换
        assertEquals("new-access", resp.getHeader(JwtAuthFilter.RESPONSE_NEW_ACCESS_HEADER));
        assertEquals("new-refresh", resp.getHeader(JwtAuthFilter.RESPONSE_NEW_REFRESH_HEADER));
        assertEquals(200, resp.getStatus(),
                "filter chain ran, so the response status is whatever the controller wrote");
    }

    @Test
    @DisplayName("access 过期 + handler 返回 empty → 仍然 401，无 response header 副作用")
    void expiredAccessWithEmptyHandlerReturns401() throws Exception {
        String expiredAccess = provider.generateAccessToken("u-1", "alice", "USER");
        Thread.sleep(50L);

        SilentRefreshHandler handler = (request, expired) -> Optional.empty();
        JwtAuthFilter filter = new JwtAuthFilter(provider, JwtBlacklist.NOOP, handler);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.addHeader("Authorization", "Bearer " + expiredAccess);
        req.addHeader("X-Refresh-Token", "stale-refresh");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = (rq, r) -> {
            throw new AssertionError("chain must NOT be invoked when handler returns empty");
        };

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(resp.getHeader(JwtAuthFilter.RESPONSE_NEW_ACCESS_HEADER),
                "no successful refresh → no X-New-Access-Token header");
        assertNull(resp.getHeader(JwtAuthFilter.RESPONSE_NEW_REFRESH_HEADER));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("access 过期 + handler 没注入 → 仍然 401，无 NPE（向后兼容）")
    void expiredAccessWithoutHandlerReturns401() throws Exception {
        String expiredAccess = provider.generateAccessToken("u-1", "alice", "USER");
        Thread.sleep(50L);

        // 老构造函数：只注入 blacklist，不注入 handler
        JwtAuthFilter filter = new JwtAuthFilter(provider, JwtBlacklist.NOOP);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.addHeader("Authorization", "Bearer " + expiredAccess);
        req.addHeader("X-Refresh-Token", "any");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = (rq, r) -> {
            throw new AssertionError("chain must NOT run when no handler is configured");
        };

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(resp.getHeader(JwtAuthFilter.RESPONSE_NEW_ACCESS_HEADER));
    }

    @Test
    @DisplayName("access 没过期 → 不走静默刷新，handler 不会被调用")
    void validAccessDoesNotInvokeHandler() throws Exception {
        // 必须用一个很长的 access TTL，避免"生成即过期"。10ms TTL 会因为生成与
        // filter 解析之间已经过了几毫秒而误判为 ExpiredJwtException。
        JwtTokenProvider longLivedProvider = new JwtTokenProvider(SECRET, 3_600_000L, 86_400_000L);
        String access = longLivedProvider.generateAccessToken("u-1", "alice", "USER");

        boolean[] handlerInvoked = {false};
        SilentRefreshHandler handler = (request, expired) -> {
            handlerInvoked[0] = true;
            return Optional.of(new SilentRefreshHandler.RefreshedTokens(
                    "u-1", "alice", "USER", "x", "y"));
        };
        JwtAuthFilter filter = new JwtAuthFilter(longLivedProvider, JwtBlacklist.NOOP, handler);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.addHeader("Authorization", "Bearer " + access);
        // 注意：没加 X-Refresh-Token —— 即便加了，valid access 路径也不该去读它
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = (rq, r) -> { /* OK, business path runs */ };

        filter.doFilter(req, resp, chain);

        assertEquals(false, handlerInvoked[0],
                "valid access must NOT trigger silent refresh — that would bypass rotation");
        assertNull(resp.getHeader(JwtAuthFilter.RESPONSE_NEW_ACCESS_HEADER));
    }

    @Test
    @DisplayName("access 过期 + refresh header 缺失 → handler 被调但返回 empty → 401")
    void expiredAccessWithoutRefreshHeaderStillReturns401() throws Exception {
        String expiredAccess = provider.generateAccessToken("u-1", "alice", "USER");
        Thread.sleep(50L);

        boolean[] handlerInvoked = {false};
        // 即使 handler 被调用，框架层面 ensureRefreshHeader 缺失也应让 handler 返回 empty
        SilentRefreshHandler handler = (request, expired) -> {
            handlerInvoked[0] = true;
            String header = request.getHeader(JwtAuthFilter.REFRESH_HEADER);
            if (header == null || header.isBlank()) return Optional.empty();
            return Optional.of(new SilentRefreshHandler.RefreshedTokens(
                    "u-1", "alice", "USER", "new-a", "new-r"));
        };
        JwtAuthFilter filter = new JwtAuthFilter(provider, JwtBlacklist.NOOP, handler);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.addHeader("Authorization", "Bearer " + expiredAccess);
        // 故意不加 X-Refresh-Token
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = (rq, r) -> {
            throw new AssertionError("chain must NOT run when refresh header is missing");
        };

        filter.doFilter(req, resp, chain);

        assertEquals(true, handlerInvoked[0],
                "filter MUST call the handler on expiry, even if the handler then refuses");
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("签名失败（非 ExpiredJwtException）+ handler 注入 → handler 不被调，直接 401")
    void signatureFailureDoesNotTriggerSilentRefresh() throws Exception {
        // 用一个不同密钥签的 token，验证签名失败。JwtTokenProvider 解析会抛 JwtException
        // （非 ExpiredJwtException 分支），应该走 401 且不调 handler。
        JwtTokenProvider other = new JwtTokenProvider(
                "b3RoZXItc2VjcmV0LWtleS1ub3QtZXF1YWwtdG8tcHJpbWFyeS1mb3ItdGVzdC1vbmx5",
                3_600_000L, 86_400_000L);
        String forged = other.generateAccessToken("u-1", "alice", "USER");

        boolean[] handlerInvoked = {false};
        SilentRefreshHandler handler = (request, expired) -> {
            handlerInvoked[0] = true;
            return Optional.empty();
        };
        JwtAuthFilter filter = new JwtAuthFilter(provider, JwtBlacklist.NOOP, handler);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.addHeader("Authorization", "Bearer " + forged);
        req.addHeader("X-Refresh-Token", "any");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = (rq, r) -> {
            throw new AssertionError("chain must NOT run on signature failure");
        };

        filter.doFilter(req, resp, chain);

        assertEquals(false, handlerInvoked[0],
                "signature failure is not ExpiredJwtException — handler must NOT be invoked");
        assertEquals(401, resp.getStatus());
    }
}