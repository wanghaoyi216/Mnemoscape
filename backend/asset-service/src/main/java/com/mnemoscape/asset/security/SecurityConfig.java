package com.mnemoscape.asset.security;

import com.mnemoscape.common.security.JwtAuthFilter;
import com.mnemoscape.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security wiring for asset-service.
 *
 * <p>asset-service exposes a mix of fully-public endpoints (local static resource
 * listings, presigned URL lookups for {@code PUBLIC} objects, anonymous static
 * file downloads) and identity-aware endpoints (private uploads, deletes) where
 * authorization is performed inside the controller / service layer using the
 * gateway-injected {@code X-User-Id} request attribute via
 * {@link com.mnemoscape.common.web.RequestContext}.
 *
 * <p>The admin-dashboard feature (Requirement 3.2) requires a second-line
 * defense: any direct call to {@code /api/v1/admin/**} that bypasses the
 * gateway MUST be rejected at the service layer with {@code hasRole('ADMIN')}.
 * To add this without disturbing the existing public-by-default behavior, this
 * configuration installs a focused {@link SecurityFilterChain} scoped via
 * {@code securityMatcher("/api/v1/admin/**")}: only admin paths flow through
 * this chain, and a {@link JwtAuthFilter} (sourced from {@code common}) is
 * spliced in to populate {@code SecurityContextHolder} from the bearer token.
 *
 * <p>All other paths fall through to a permissive secondary chain that does
 * not require authentication at this layer, preserving the pre-admin-dashboard
 * contract that the gateway is the primary auth boundary.
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

    /**
     * Admin-scoped chain (order=0): only {@code /api/v1/admin/**} flows through
     * here. The chain is stateless, requires the bearer token to be valid, and
     * gates every admin path on {@code ROLE_ADMIN}.
     */
    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
                                                         JwtTokenProvider jwtTokenProvider) throws Exception {
        http
            .securityMatcher("/api/v1/admin/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtAuthFilter(jwtTokenProvider),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Permissive fallback chain for every non-admin path.
     *
     * <p>asset-service relies on the gateway for primary authentication and on
     * the controller layer ({@link com.mnemoscape.common.web.RequestContext})
     * for per-object authorization. This chain therefore only disables CSRF
     * and session creation and lets requests through unauthenticated; without
     * it, the auto-configured Spring Security defaults would lock down every
     * endpoint and break public static-resource access.
     */
    @Bean
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );

        return http.build();
    }
}
