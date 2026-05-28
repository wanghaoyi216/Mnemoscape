package com.mnemoscape.auth.service;

import com.mnemoscape.auth.model.dto.AuthResponse;
import com.mnemoscape.auth.model.dto.LoginRequest;
import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.auth.security.RedisJwtBlacklist;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AuthService#login(LoginRequest)} (admin-dashboard task
 * 2.7, validates Requirement 2.1).
 *
 * <p>Verifies the end-to-end role propagation: a user record carrying
 * {@code role="ADMIN"} → login response carries {@code role="ADMIN"} AND
 * the embedded JWT decodes back to {@code role=ADMIN}.
 */
class AuthServiceLoginTest {

    private static final String SECRET =
            "bW5lbW9zY2FwZS1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni1hbGdvcml0aG0tMjAyNQ==";

    private UserRepository repo;
    private RedisJwtBlacklist blacklist;
    private JwtTokenProvider provider;
    private BCryptPasswordEncoder encoder;
    private AuthService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        blacklist = mock(RedisJwtBlacklist.class);
        provider = new JwtTokenProvider(SECRET, 3_600_000L, 86_400_000L);
        encoder = new BCryptPasswordEncoder();
        service = new AuthService(repo, provider, encoder, blacklist);
        // Inject the @Value field that's normally bound by Spring.
        ReflectionTestUtils.setField(service, "accessTokenExpiration", 3_600_000L);
    }

    private User userWithRole(String username, String rawPassword, String role) {
        User u = User.builder()
                .id("u-" + username)
                .username(username)
                .email(username + "@example.com")
                .passwordHash(encoder.encode(rawPassword))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        u.setRole(role);
        return u;
    }

    @Test
    @DisplayName("ADMIN user login returns role=ADMIN and a JWT carrying the role claim")
    void adminLoginPropagatesRoleEndToEnd() {
        User admin = userWithRole("alice", "p@ssw0rd", "ADMIN");
        when(repo.findByUsername("alice")).thenReturn(Optional.of(admin));

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("p@ssw0rd");

        AuthResponse resp = service.login(req);

        assertEquals("ADMIN", resp.getRole(),
                "AuthResponse must carry the user's role");
        assertNotNull(resp.getAccessToken());

        // Decode the JWT and verify the role claim is also ADMIN.
        Claims claims = provider.validateToken(resp.getAccessToken());
        assertEquals("ADMIN", claims.get("role", String.class),
                "JWT role claim must match AuthResponse.role");
        assertEquals("u-alice", claims.getSubject());
        assertEquals("alice", claims.get("username", String.class));
    }

    @Test
    @DisplayName("USER login returns role=USER and a USER-claim JWT")
    void userLoginCarriesUserRole() {
        User user = userWithRole("bob", "secret-bob", "USER");
        when(repo.findByUsername("bob")).thenReturn(Optional.of(user));

        LoginRequest req = new LoginRequest();
        req.setUsername("bob");
        req.setPassword("secret-bob");

        AuthResponse resp = service.login(req);
        assertEquals("USER", resp.getRole());

        Claims claims = provider.validateToken(resp.getAccessToken());
        assertEquals("USER", claims.get("role", String.class));
    }

    @Test
    @DisplayName("Wrong password rejects login regardless of role")
    void wrongPasswordRejected() {
        User admin = userWithRole("alice", "right-password", "ADMIN");
        when(repo.findByUsername("alice")).thenReturn(Optional.of(admin));

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("wrong-password");

        BizException ex = assertThrows(BizException.class, () -> service.login(req));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("Missing user produces same 401 as wrong password (no enumeration)")
    void missingUserProducesSameError() {
        when(repo.findByUsername("ghost")).thenReturn(Optional.empty());

        LoginRequest req = new LoginRequest();
        req.setUsername("ghost");
        req.setPassword("any");

        BizException ex = assertThrows(BizException.class, () -> service.login(req));
        assertEquals(401, ex.getCode());
        // Same message as wrongPasswordRejected — defends against enumeration.
        assertEquals("Invalid credentials", ex.getMessage());
    }

    @Test
    @DisplayName("Refresh token never carries ADMIN role even for admin users")
    void refreshTokenAlwaysUserRole() {
        User admin = userWithRole("alice", "pwd", "ADMIN");
        when(repo.findByUsername("alice")).thenReturn(Optional.of(admin));

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("pwd");

        AuthResponse resp = service.login(req);
        Claims refreshClaims = provider.validateToken(resp.getRefreshToken());
        assertEquals("USER", refreshClaims.get("role", String.class),
                "refresh token must not carry admin authority");
    }
}
