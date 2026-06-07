package com.mnemoscape.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-based test for {@link JwtAuthFilter}'s role-claim → authority
 * mapping (admin-dashboard task 1.2 / Property 12, validates Requirements
 * 2.3 / 2.4).
 *
 * <h2>Property 12 — Authority mapping is total</h2>
 * <pre>
 *     role claim "ADMIN"   ⟹  ROLE_ADMIN
 *     role claim "USER"    ⟹  ROLE_USER
 *     anything else / null ⟹  ROLE_USER  (with WARN log; verified via attribute)
 * </pre>
 *
 * <p>The shipping {@link JwtTokenProvider#generateAccessToken(String,
 * String, String)} normalises the claim to one of the two canonical values
 * before signing, so to drive the filter through the "anything else" branch
 * we sign tokens directly with a hand-built {@link Jwts.builder()}. This is
 * the only way to plant arbitrary role values into the JWT.
 */
class JwtAuthFilterPropTest {

    /** Same secret bootstrap procedure as JwtTokenProvider. */
    private static final String SECRET =
            "bW5lbW9zY2FwZS1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni1hbGdvcml0aG0tMjAyNQ==";
    private static final SecretKey KEY = buildKey(SECRET);

    private static SecretKey buildKey(String secret) {
        byte[] keyBytes = Base64.getDecoder().decode(
                Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private static final JwtTokenProvider PROVIDER =
            new JwtTokenProvider(SECRET, 3_600_000L, 86_400_000L);

    /**
     * Sign a JWT with an arbitrary role value (which {@link JwtTokenProvider}
     * would otherwise normalise away). This is the bypass that lets us
     * exercise the filter's "unexpected value" branch.
     */
    private static String signWithRole(String userId, String username, String roleClaim) {
        Date now = new Date();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000L));
        if (roleClaim != null) {
            // Keep the raw role string (do NOT normalise) so the filter's
            // strict {"ADMIN","USER"} guard is exercised.
            builder.claim("role", roleClaim);
        }
        return builder.signWith(KEY).compact();
    }

    /**
     * Run the filter against a request carrying {@code token} and capture the
     * resolved role attribute and authority. Uses a no-op blacklist so jti
     * checks never short-circuit.
     */
    private static FilterOutcome runFilter(String token) throws IOException {
        JwtAuthFilter filter = new JwtAuthFilter(PROVIDER, JwtBlacklist.NOOP);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/test");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedRoleAttr = new AtomicReference<>();
        AtomicReference<String> capturedAuthority = new AtomicReference<>();

        FilterChain chain = (req, resp) -> {
            HttpServletRequest hReq = (HttpServletRequest) req;
            // Snapshot inside the chain — JwtAuthFilter clears
            // SecurityContextHolder in its `finally` block.
            Object roleAttr = hReq.getAttribute("role");
            if (roleAttr != null) capturedRoleAttr.set(roleAttr.toString());
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && !auth.getAuthorities().isEmpty()) {
                capturedAuthority.set(auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.joining(",")));
            }
        };
        try {
            filter.doFilter(request, response, chain);
        } catch (jakarta.servlet.ServletException e) {
            throw new RuntimeException(e);
        }
        return new FilterOutcome(
                response.getStatus(),
                capturedRoleAttr.get(),
                capturedAuthority.get());
    }

    private record FilterOutcome(int status, String roleAttr, String authority) {}

    /**
     * Property 12 — for any role claim string, the filter MUST resolve to
     * exactly one of {@code ROLE_ADMIN} / {@code ROLE_USER}. The mapping is
     * total: no input drops the request to a 401 unauthorised path because
     * of an unknown role.
     */
    @Property
    void anyRoleClaimMapsToOneOfTwoAuthorities(@ForAll @RoleClaimProvider String roleClaim) throws IOException {
        String token = signWithRole("u1", "alice", roleClaim);
        FilterOutcome outcome = runFilter(token);
        assertEquals(HttpServletResponse.SC_OK, outcome.status(),
                "filter must let the request through for any role value");
        assertNotNull(outcome.authority(), "SecurityContext must carry an authority");
        assertTrue(
                outcome.authority().equals("ROLE_ADMIN") || outcome.authority().equals("ROLE_USER"),
                "authority must be one of ROLE_ADMIN / ROLE_USER, got: " + outcome.authority());
    }

    @Property
    void onlyAdminClaimResolvesToRoleAdmin(@ForAll @RoleClaimProvider String roleClaim) throws IOException {
        String token = signWithRole("u1", "alice", roleClaim);
        FilterOutcome outcome = runFilter(token);
        if ("ADMIN".equals(roleClaim)) {
            assertEquals("ROLE_ADMIN", outcome.authority());
            assertEquals("ADMIN", outcome.roleAttr());
        } else {
            assertEquals("ROLE_USER", outcome.authority(),
                    "non-canonical role claim must downgrade to ROLE_USER, got: "
                            + outcome.authority() + " for input: " + roleClaim);
            assertEquals("USER", outcome.roleAttr());
        }
    }

    @Provide
    Arbitrary<String> arbitraryRoleClaims() {
        // Cover the full lattice:
        //   - canonical values (ADMIN / USER)
        //   - lowercase / mixed-case (must downgrade)
        //   - whitespace padding (must downgrade)
        //   - aliases / nonsense / null
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(2, Arbitraries.of("ADMIN", "USER")),
                net.jqwik.api.Tuple.of(2, Arbitraries.of("admin", "user", "Admin", "User")),
                net.jqwik.api.Tuple.of(1, Arbitraries.of(" ADMIN", "ADMIN ", "ADMIN\t")),
                net.jqwik.api.Tuple.of(2, Arbitraries.of("MOD", "MODERATOR", "GUEST", "OWNER")),
                net.jqwik.api.Tuple.of(1, Arbitraries.strings().ofMaxLength(8)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just((String) null))
        );
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER)
    @net.jqwik.api.From("arbitraryRoleClaims")
    @interface RoleClaimProvider {}

    @Test
    void canonicalAdminTokenFromProviderResolvesToRoleAdmin() throws IOException {
        // Sanity: the production code path (JwtTokenProvider with role="ADMIN")
        // and the filter wire up correctly end-to-end.
        String token = PROVIDER.generateAccessToken("u1", "alice", "ADMIN");
        FilterOutcome outcome = runFilter(token);
        assertEquals(HttpServletResponse.SC_OK, outcome.status());
        assertEquals("ROLE_ADMIN", outcome.authority());
    }

    @Test
    void canonicalUserTokenFromProviderResolvesToRoleUser() throws IOException {
        String token = PROVIDER.generateAccessToken("u1", "alice", "USER");
        FilterOutcome outcome = runFilter(token);
        assertEquals(HttpServletResponse.SC_OK, outcome.status());
        assertEquals("ROLE_USER", outcome.authority());
    }
}
