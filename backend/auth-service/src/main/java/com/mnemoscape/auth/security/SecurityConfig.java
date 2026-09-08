package com.mnemoscape.auth.security;

import com.mnemoscape.common.security.JwtAuthFilter;
import com.mnemoscape.common.security.JwtBlacklist;
import com.mnemoscape.common.security.JwtTokenProvider;
import com.mnemoscape.common.security.SilentRefreshHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * R21 SecurityConfig —— Authorization Server / Resource Server 端点编排。
 *
 * <p>本服务同时承担两个角色：
 * <ul>
 *   <li><b>Authorization Server</b> —— 由 {@link AuthorizationServerConfig} 提供
 *       {@code /oauth2/authorize}、{@code /oauth2/token}、{@code /oauth2/jwks}、
 *       {@code /oauth2/revoke}、{@code /userinfo} 等 OAuth2/OIDC 端点。</li>
 *   <li><b>Resource Server</b> —— 自身的 /api/v1/admin/** 等业务接口继续使用
 *       {@link JwtTokenProvider} 校验 Bearer token（与 R14 兼容）；新接入的
 *       服务（如 memory-service、ai-service）则改用 Spring Security 的
 *       {@code spring-security-oauth2-resource-server} + JwtDecoder 走 JWKS 校验。</li>
 * </ul>
 *
 * <p><b>代码量：</b>R14 时期 SecurityConfig 145 行（含 cors / bcrypt / permitAll
 * 列表 / filter 装配），本版本合并 OAuth2 编排后约 130 行 —— 但 R21 把"手写
 * JwtSigner"（即 common.JwtTokenProvider 在 OAuth2 端点的使用）整体抹掉了，
 * Authorization Server 端点本身不依赖任何 jjwt 代码。
 *
 * <p><b>R14 → R21 兼容：</b>{@link R21RevocationHookConfiguration} 负责把
 * {@code /oauth2/revoke} 与 refresh-token 旋转事件桥接到现有的
 * {@link TokenBlacklistService} / {@link RefreshTokenStore}。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${mnemoscape.jwt.secret}")
    private String jwtSecret;

    @Value("${mnemoscape.jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Value("${mnemoscape.jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    @Bean
    public JwtTokenProvider jwtTokenProvider() {
        return new JwtTokenProvider(jwtSecret, accessTokenExpiration, refreshTokenExpiration);
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 默认 SecurityFilterChain —— 业务接口（/api/v1/admin/**、/api/v1/auth/login 等）。
     *
     * <p>OAuth2 端点（/oauth2/**、/userinfo、/.well-known/**）由
     * {@link AuthorizationServerConfig#authorizationServerSecurityFilterChain(HttpSecurity)}
     * 单独处理（{@code @Order(HIGHEST_PRECEDENCE)}），这里只关心业务接口。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain businessApiFilterChain(HttpSecurity http,
                                                      JwtTokenProvider jwtTokenProvider,
                                                      JwtBlacklist blacklist,
                                                      SilentRefreshHandler silentRefreshHandler) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/auth/refresh",
                        "/actuator/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/webjars/**",
                        "/ws/**"
                ).permitAll()
                // Internal Feign-only batch lookup used by memory-service
                // top-contributors aggregator (admin-dashboard R10.2). The
                // gateway intentionally does not route /api/v1/users/** to
                // auth-service (see api-gateway application.yml routes), so
                // this path is unreachable from external traffic — the path-
                // routing layer is the perimeter, not the JWT filter. Adding
                // a Bearer-relay Feign interceptor in every caller would be
                // strictly more code for no extra defence. (R10.4 / R15.2:
                // the response payload itself remains a strict {userId,
                // username} whitelist.)
                .requestMatchers(HttpMethod.POST, "/api/v1/users/batch-usernames")
                    .permitAll()
                // Bootstrap role-promotion endpoint: callers carrying the
                // X-Bootstrap-Secret header are forwarded to the service layer
                // for constant-time secret comparison; everyone else must already
                // hold ROLE_ADMIN. The path itself still falls under /api/v1/admin/**
                // and is double-checked in the gateway, so this only relaxes the
                // service-local rule for the bootstrap header path.
                // (admin-dashboard Requirements 1.5)
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/users/*/role")
                    .access(adminOrBootstrapSecret())
                // All remaining /api/v1/admin/** paths require ROLE_ADMIN at the
                // service layer to defend against direct calls that bypass the
                // gateway. (admin-dashboard Requirements 3.2)
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            // R14：把 silentRefreshHandler 注入 JwtAuthFilter，access 过期时会自动
            // 尝试用 X-Refresh-Token 旋转并把新 token 写回 response header。
            .addFilterBefore(new JwtAuthFilter(jwtTokenProvider, blacklist, silentRefreshHandler),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:8080"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * AuthorizationManager for {@code POST /api/v1/admin/users/{id}/role}.
     *
     * <p>The bootstrap role-promotion endpoint must remain reachable when no admin
     * exists yet. We let any request carrying a non-blank {@code X-Bootstrap-Secret}
     * header through the security layer so the {@code AdminBootstrapService} can
     * perform a constant-time comparison against the configured secret and decide
     * to promote / reject. Without that header, the request must already be
     * authenticated as {@code ROLE_ADMIN}.
     *
     * <p>Note: granting access here is intentionally permissive. The actual secret
     * verification happens at the service layer; the gateway also enforces the
     * outer admin path ADMIN guard, but the bootstrap header is specifically
     * allowed there too. (admin-dashboard Requirements 1.5)
     */
    private static AuthorizationManager<RequestAuthorizationContext> adminOrBootstrapSecret() {
        AuthorityAuthorizationManager<RequestAuthorizationContext> adminCheck =
                AuthorityAuthorizationManager.hasRole("ADMIN");
        return (authentication, context) -> {
            String secret = context.getRequest().getHeader("X-Bootstrap-Secret");
            if (secret != null && !secret.isBlank()) {
                return new AuthorizationDecision(true);
            }
            return adminCheck.check(authentication, context);
        };
    }
}
