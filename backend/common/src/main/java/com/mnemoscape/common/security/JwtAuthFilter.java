package com.mnemoscape.common.security;

import com.mnemoscape.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
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

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtBlacklist blacklist;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider) {
        this(jwtTokenProvider, JwtBlacklist.NOOP);
    }

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider, JwtBlacklist blacklist) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.blacklist = blacklist == null ? JwtBlacklist.NOOP : blacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws IOException {
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

            request.setAttribute("userId", userId);
            request.setAttribute("username", username);

            // Tell Spring Security this request is authenticated so
            // .authorizeHttpRequests().anyRequest().authenticated() lets it through.
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        } catch (Exception e) {
            log.debug("JWT rejected reason={} path={}", e.getClass().getSimpleName(), request.getRequestURI());
            writeUnauthorized(response, "Invalid or expired token");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.unauthorized(message));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/actuator")
                || path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.equals("/ws")
                || path.startsWith("/ws/");
    }
}
