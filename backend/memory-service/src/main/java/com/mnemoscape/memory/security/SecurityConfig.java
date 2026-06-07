package com.mnemoscape.memory.security;

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
 * Spring Security wiring for memory-service.
 *
 * <p>memory-service has historically relied on the api-gateway as the primary
 * authentication boundary and on the controller layer
 * ({@link com.mnemoscape.common.web.RequestContext}) for per-record
 * authorization driven by the gateway-injected {@code X-User-Id} request
 * attribute. This contract is preserved for every non-admin path.
 *
 * <p>The admin-dashboard feature (Requirement 3.2) requires a second-line
 * defense: any direct call to {@code /api/v1/admin/**} that bypasses the
 * gateway MUST be rejected at the service layer with {@code hasRole('ADMIN')}.
 * The configuration installs a focused {@link SecurityFilterChain} scoped via
 * {@code securityMatcher("/api/v1/admin/**")} that authenticates the bearer
 * token through the shared {@link JwtAuthFilter} (which populates the
 * {@code SecurityContextHolder} with {@code ROLE_ADMIN} or {@code ROLE_USER}
 * derived from the JWT {@code role} claim, see Requirements 2.3 / 2.4).
 *
 * <p>All other paths fall through to a permissive secondary chain that does
 * not require authentication at this layer.
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
     * Admin-scoped chain (default order): only {@code /api/v1/admin/**} flows
     * through here. The chain is stateless, requires the bearer token to be
     * valid, and gates every admin path on {@code ROLE_ADMIN}.
     *
     * <p>The {@code requestMatchers("/api/v1/admin/**").hasRole("ADMIN")} rule
     * is placed before {@code anyRequest().authenticated()} so the chain still
     * has a default-deny fallback should the matcher be tightened in the
     * future. (admin-dashboard Requirements 3.2)
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
     * <p>memory-service relies on the gateway for primary authentication and on
     * the controller layer ({@link com.mnemoscape.common.web.RequestContext})
     * for per-record authorization. This chain therefore only disables CSRF
     * and session creation and lets requests through unauthenticated; without
     * it, the auto-configured Spring Security defaults would lock down every
     * endpoint and break the existing contract.
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
