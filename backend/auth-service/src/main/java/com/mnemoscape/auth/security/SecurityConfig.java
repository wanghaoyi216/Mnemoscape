package com.mnemoscape.auth.security;

import com.mnemoscape.common.security.JwtAuthFilter;
import com.mnemoscape.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtTokenProvider jwtTokenProvider,
                                            RedisJwtBlacklist blacklist) throws Exception {
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
            .addFilterBefore(new JwtAuthFilter(jwtTokenProvider, blacklist),
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
