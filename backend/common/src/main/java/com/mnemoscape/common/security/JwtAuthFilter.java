package com.mnemoscape.common.security;

import com.mnemoscape.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Servlet filter that validates the JWT in the {@code Authorization: Bearer ...} header,
 * populates the request attributes used by downstream controllers, AND sets
 * Spring Security's {@link SecurityContextHolder} so subsequent
 * {@code .authenticated()} authorization rules see the authenticated principal.
 *
 * <p>Public paths (login / register / refresh / actuator / websocket handshake)
 * are skipped via {@link #shouldNotFilter(HttpServletRequest)}.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /**
     * Client 在 access token 之外携带 refresh token 的 header 名。
     * 静默刷新流程会从这里取 refresh token 用于 rotation。
     */
    public static final String REFRESH_HEADER = "X-Refresh-Token";
    /** 静默刷新成功后，过滤器把新 access / refresh 写入这两个 response header，供前端透明替换。 */
    public static final String RESPONSE_NEW_ACCESS_HEADER = "X-New-Access-Token";
    public static final String RESPONSE_NEW_REFRESH_HEADER = "X-New-Refresh-Token";

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtBlacklist blacklist;
    private final SilentRefreshHandler silentRefreshHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider) {
        this(jwtTokenProvider, JwtBlacklist.NOOP, null);
    }

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider, JwtBlacklist blacklist) {
        this(jwtTokenProvider, blacklist, null);
    }

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider,
                         JwtBlacklist blacklist,
                         SilentRefreshHandler silentRefreshHandler) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.blacklist = blacklist == null ? JwtBlacklist.NOOP : blacklist;
        this.silentRefreshHandler = silentRefreshHandler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws IOException, ServletException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            writeUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            writeUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        try {
            var claims = jwtTokenProvider.validateToken(token);
            String userId = claims.getSubject();
            String username = claims.get("username", String.class);
            String jti = claims.getId();

            // 已被显式 logout 或踢人的 token 立即拒绝
            if (jti != null && blacklist.isBlacklisted(jti)) {
                log.debug("Rejecting blacklisted jti={} path={}", jti, request.getRequestURI());
                writeUnauthorized(response, "Session revoked");
                return;
            }

            // Resolve the role claim with strict, case-sensitive validation against the
            // allowed set {"USER", "ADMIN"} (admin-dashboard Requirements 2.3, 2.4).
            // Missing or unexpected values downgrade to USER and emit a WARN log; the
            // request is still allowed to continue through the filter chain.
            String roleClaim = claims.get("role", String.class);

            authenticateAs(request, response, filterChain, userId, username, roleClaim);
            return;
        } catch (ExpiredJwtException expired) {
            // R14 静默刷新扩展：access 过期但 refresh 仍有效时，自动旋转并把新 token
            // 写回 response header。前端读取 X-New-Access-Token / X-New-Refresh-Token
            // 即可透明替换本地存储，业务控制器完全无感。
            //
            // 没注入 SilentRefreshHandler、或 handler 找不到有效 refresh token，
            // 一律回退到标准 401，避免误放行未鉴权请求。
            if (silentRefreshHandler != null) {
                Optional<SilentRefreshHandler.RefreshedTokens> refreshed =
                        silentRefreshHandler.tryRefresh(request, token);
                if (refreshed.isPresent()) {
                    SilentRefreshHandler.RefreshedTokens rt = refreshed.get();
                    // 把新一对 token 写进 response header。客户端 axios/fetch
                    // 拦截器读这两个 header 即可完成本地替换，无需修改业务代码。
                    response.setHeader(RESPONSE_NEW_ACCESS_HEADER, rt.accessToken());
                    response.setHeader(RESPONSE_NEW_REFRESH_HEADER, rt.refreshToken());
                    authenticateAs(request, response, filterChain,
                            rt.userId(), rt.username(), rt.role());
                    return;
                }
            }
            log.debug("Access expired, no silent refresh path available path={}",
                    request.getRequestURI());
            writeUnauthorized(response, "Invalid or expired token");
        } catch (Exception e) {
            log.debug("JWT rejected reason={} path={}", e.getClass().getSimpleName(), request.getRequestURI());
            writeUnauthorized(response, "Invalid or expired token");
        }
    }

    /**
     * 用解析后的 claims 在 SecurityContext 里建立认证主体，并继续走下游过滤器链。
     * 抽取出来是为了让"主路径"和"静默刷新路径"共享同一套 Spring Security 注入逻辑，
     * 避免两处代码漂移导致 role 规范化或 clearContext 顺序不一致。
     */
    private void authenticateAs(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain filterChain,
                                String userId,
                                String username,
                                String roleClaim) throws IOException, ServletException {
        String resolvedRole;
        if ("ADMIN".equals(roleClaim) || "USER".equals(roleClaim)) {
            resolvedRole = roleClaim;
        } else {
            resolvedRole = "USER";
            if (roleClaim == null) {
                log.warn("JWT role claim is missing on path={}; downgrading to USER",
                        request.getRequestURI());
            } else {
                log.warn("JWT role claim has unexpected value '{}' on path={}; downgrading to USER",
                        sanitize(roleClaim), request.getRequestURI());
            }
        }

        request.setAttribute("userId", userId);
        request.setAttribute("username", username);
        request.setAttribute("role", resolvedRole);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + resolvedRole)));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.unauthorized(message));
    }

    /**
     * Truncate and scrub a claim value before logging to defend against
     * log-injection / log-forging via crafted JWT claims.  The output is
     * limited to 32 characters and any control characters (CR / LF / TAB
     * and other C0 / DEL bytes) are replaced with '?'.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        int max = 32;
        String trimmed = value.length() > max ? value.substring(0, max) + "…" : value;
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/actuator")
                || path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.equals("/ws")
                || path.startsWith("/ws/")) {
            return true;
        }
        // admin-dashboard R1.4 / R1.5: the role-promotion endpoint is the
        // documented bootstrap path used to mint the platform's first ADMIN.
        // When it carries an {@code X-Bootstrap-Secret} header AND no
        // {@code Authorization} header we let it skip JWT validation entirely,
        // so the request can reach Spring Security's
        // {@code adminOrBootstrapSecret()} authorization manager (which lets
        // it through to {@code AdminBootstrapService} for constant-time
        // secret comparison). When an authenticated admin instead drives the
        // promotion via {@code Authorization: Bearer <jwt>}, JWT validation
        // runs as usual so {@code SecurityContextHolder} carries the caller's
        // ROLE_ADMIN authority.
        if ("POST".equalsIgnoreCase(request.getMethod())
                && path.matches("^/api/v1/admin/users/[^/]+/role$")) {
            String bootstrap = request.getHeader("X-Bootstrap-Secret");
            String auth = request.getHeader("Authorization");
            if (bootstrap != null && !bootstrap.isBlank()
                    && (auth == null || auth.isBlank())) {
                return true;
            }
        }
        return false;
    }
}
