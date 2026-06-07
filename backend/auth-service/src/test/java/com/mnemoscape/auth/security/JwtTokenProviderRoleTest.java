package com.mnemoscape.auth.security;

import com.mnemoscape.common.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JwtTokenProvider#generateAccessToken(String, String,
 * String)} (admin-dashboard task 2.5, validates Requirements 2.1 / 2.5).
 *
 * <p>Verifies:
 * <ul>
 *   <li>The new three-arg signature carries a {@code role} claim equal to the
 *       input (post-normalisation).</li>
 *   <li>The legacy two-arg signature still works AND defaults the role to
 *       {@code "USER"} so older code paths don't accidentally mint admin
 *       tokens.</li>
 *   <li>Subject / username / jti / exp are preserved across both signatures.</li>
 *   <li>Unexpected role inputs (lowercase / null / blank / aliases) are
 *       normalised to {@code "USER"} at sign time so the wire never carries
 *       garbage.</li>
 * </ul>
 */
class JwtTokenProviderRoleTest {

    private static final String SECRET =
            "bW5lbW9zY2FwZS1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni1hbGdvcml0aG0tMjAyNQ==";
    private static final long ACCESS_TTL = 3_600_000L;
    private static final long REFRESH_TTL = 86_400_000L;

    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, ACCESS_TTL, REFRESH_TTL);

    @Test
    @DisplayName("ADMIN role round-trips into the role claim")
    void adminRoleIsPersistedInToken() {
        String token = provider.generateAccessToken("u1", "alice", "ADMIN");
        Claims claims = provider.validateToken(token);
        assertEquals("ADMIN", claims.get("role", String.class));
        assertEquals("u1", claims.getSubject());
        assertEquals("alice", claims.get("username", String.class));
        assertNotNull(claims.getId(), "jti must be present");
        assertNotNull(claims.getExpiration(), "exp must be present");
    }

    @Test
    @DisplayName("USER role round-trips into the role claim")
    void userRoleIsPersistedInToken() {
        String token = provider.generateAccessToken("u1", "alice", "USER");
        Claims claims = provider.validateToken(token);
        assertEquals("USER", claims.get("role", String.class));
    }

    @Test
    @DisplayName("Legacy two-arg signature defaults role to USER")
    void legacyTwoArgSignatureDefaultsToUser() {
        String token = provider.generateAccessToken("u1", "alice");
        Claims claims = provider.validateToken(token);
        assertEquals("USER", claims.get("role", String.class),
                "old call sites must not be able to mint admin tokens accidentally");
    }

    @Test
    @DisplayName("Lowercase / null / blank role normalises to USER on the wire")
    void unexpectedRoleNormalisesToUser() {
        for (String input : new String[]{"admin", "Admin", "", " ", null, "MODERATOR", "OWNER"}) {
            String token = provider.generateAccessToken("u1", "alice", input);
            Claims claims = provider.validateToken(token);
            assertEquals("USER", claims.get("role", String.class),
                    "input \"" + input + "\" should normalise to USER");
        }
    }

    @Test
    @DisplayName("subject / username / jti are independent of role choice")
    void coreClaimsAreIndependentOfRole() {
        String token1 = provider.generateAccessToken("uABC", "carol", "USER");
        String token2 = provider.generateAccessToken("uABC", "carol", "ADMIN");
        Claims c1 = provider.validateToken(token1);
        Claims c2 = provider.validateToken(token2);

        assertEquals("uABC", c1.getSubject());
        assertEquals("uABC", c2.getSubject());
        assertEquals("carol", c1.get("username", String.class));
        assertEquals("carol", c2.get("username", String.class));
        // Each call mints a fresh jti — same caller, two distinct tokens.
        assertNotEquals(c1.getId(), c2.getId(),
                "every issuance must produce a unique jti");
    }

    @Test
    @DisplayName("Token expiration falls within the configured access TTL window")
    void expirationIsWithinTtl() {
        long before = System.currentTimeMillis();
        String token = provider.generateAccessToken("u1", "alice", "ADMIN");
        long after = System.currentTimeMillis();
        long exp = provider.validateToken(token).getExpiration().getTime();
        assertTrue(exp >= before + ACCESS_TTL - 1_000L,
                "exp must be at least now + (TTL - 1s slack)");
        assertTrue(exp <= after + ACCESS_TTL + 1_000L,
                "exp must be at most now + (TTL + 1s slack)");
    }

    @Test
    @DisplayName("Refresh token always has USER role (it doesn't drive authz)")
    void refreshTokenAlwaysCarriesUserRole() {
        String token = provider.generateRefreshToken("u1", "alice");
        Claims claims = provider.validateToken(token);
        assertEquals("USER", claims.get("role", String.class),
                "refresh tokens must not carry admin authority");
    }
}
