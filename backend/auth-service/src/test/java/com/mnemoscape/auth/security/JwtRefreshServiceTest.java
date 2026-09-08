package com.mnemoscape.auth.security;

import com.mnemoscape.auth.model.dto.AuthResponse;
import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.security.JwtBlacklist;
import com.mnemoscape.common.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JwtRefreshService} — R14 task 5.1 核心。
 *
 * <p>三个核心场景：
 * <ul>
 *   <li><b>rotation 一次性使用</b> —— 同一个 refresh token 第二次调
 *       validateAndRotate 必须抛 401，因为白名单里已被删除。</li>
 *   <li><b>黑名单命中</b> —— refresh token 的 jti 在 JwtBlacklist.isRefreshBlacklisted
 *       命中时，validateAndRotate 必须立刻拒绝。</li>
 *   <li><b>revokeAllForUser</b> —— 清空指定 userId 的所有 refresh token 后，
 *       该用户的任何 refresh token 都无法再换发新 token。</li>
 * </ul>
 */
class JwtRefreshServiceTest {

    private static final String SECRET =
            "bW5lbW9zY2FwZS1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni1hbGdvcml0aG0tMjAyNQ==";
    private static final long ACCESS_TTL = 3_600_000L;
    private static final long REFRESH_TTL = 86_400_000L;

    private UserRepository userRepository;
    private RefreshTokenStore refreshStore;
    private JwtBlacklist blacklist;
    private TokenBlacklistService tokenBlacklistService;
    private JwtTokenProvider jwtTokenProvider;
    private JwtRefreshService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshStore = mock(RefreshTokenStore.class);
        blacklist = mock(JwtBlacklist.class);
        tokenBlacklistService = mock(TokenBlacklistService.class);
        jwtTokenProvider = new JwtTokenProvider(SECRET, ACCESS_TTL, REFRESH_TTL);
        service = new JwtRefreshService(jwtTokenProvider, refreshStore, userRepository,
                blacklist, tokenBlacklistService);
        ReflectionTestUtils.setField(service, "accessTokenExpiration", ACCESS_TTL);
    }

    private User userWithRole(String userId, String username, String role) {
        User u = User.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .passwordHash("hash-not-relevant-here")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        u.setRole(role);
        return u;
    }

    // -----------------------------------------------------------------------
    // issueRefreshToken
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("issueRefreshToken: returns a JWT, registers jti in store with the remaining TTL")
    void issueStoresJtiInStore() {
        User user = userWithRole("u-1", "alice", "USER");
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));

        JwtRefreshService.IssuedRefreshToken issued = service.issueRefreshToken("u-1");

        assertNotNull(issued.refreshToken());
        assertNotNull(issued.jti(), "issued jti must be non-null — JwtTokenProvider always stamps one");
        assertTrue(issued.ttlMillis() > 0, "ttl must equal refresh-token-remaining lifetime");

        verify(refreshStore).store(eq("u-1"), eq(issued.jti()), eq(issued.ttlMillis()));
    }

    @Test
    @DisplayName("issueRefreshToken: 404 when userId doesn't exist")
    void issueUnknownUser() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.issueRefreshToken("ghost"));
    }

    @Test
    @DisplayName("issueRefreshToken: blank userId → 400 without consulting DB")
    void issueBlankUserId() {
        assertThrows(BizException.class, () -> service.issueRefreshToken(""));
        verify(userRepository, never()).findById(anyString());
    }

    // -----------------------------------------------------------------------
    // validateAndRotate — happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("validateAndRotate: returns new pair, deletes old jti from store")
    void rotationReturnsNewPairAndDeletesOldJti() {
        User user = userWithRole("u-1", "alice", "USER");
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        // First lookup: jti is active. After rotation we don't care about further lookups.
        when(refreshStore.isActive(eq("u-1"), anyString())).thenReturn(true);

        // Mint a real refresh token so the JWT is valid against the same secret.
        String oldRefresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");

        JwtRefreshService.RotationResult result = service.validateAndRotate(oldRefresh);

        assertNotNull(result.authResponse());
        assertEquals("u-1", result.userId());
        assertEquals("alice", result.username());
        assertEquals("USER", result.role());
        assertNotNull(result.authResponse().getAccessToken());
        assertNotNull(result.authResponse().getRefreshToken());

        String oldJti = jwtTokenProvider.getJti(oldRefresh);
        verify(refreshStore).revoke("u-1", oldJti);
        // The NEW jti must be re-stored.
        String newJti = jwtTokenProvider.getJti(result.authResponse().getRefreshToken());
        assertNotEquals(oldJti, newJti, "rotation must mint a fresh jti — not reuse the old one");
        verify(refreshStore).store(eq("u-1"), eq(newJti), anyLong());
    }

    // -----------------------------------------------------------------------
    // validateAndRotate — rotation semantics (R14 task 5.1: refresh rotation
    // makes the old token immediately invalid)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("rotation: using the SAME token again immediately fails (old jti already revoked)")
    void rotationIsOneShot() {
        User user = userWithRole("u-1", "alice", "USER");
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));

        // First call: store says jti is active.
        when(refreshStore.isActive(eq("u-1"), anyString())).thenReturn(true);
        String oldRefresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");

        // Rotate once — succeeds.
        service.validateAndRotate(oldRefresh);

        // Second call with the SAME oldRefresh must fail. Simulate the post-revoke
        // state by having store report the old jti as no longer active.
        when(refreshStore.isActive(eq("u-1"), anyString())).thenReturn(false);
        BizException ex = assertThrows(BizException.class,
                () -> service.validateAndRotate(oldRefresh));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().toLowerCase().contains("revoked")
                        || ex.getMessage().toLowerCase().contains("invalid"),
                "reused refresh token must surface as 401/revoked");
    }

    // -----------------------------------------------------------------------
    // validateAndRotate — blacklist hit (R14 task 5.1)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("validateAndRotate: blacklist hit on the refresh jti → 401")
    void blacklistHitRejectsRotation() {
        // validateRefreshToken 抛 IllegalStateException 当 jti 在 refresh 黑名单
        when(blacklist.isRefreshBlacklisted(anyString())).thenReturn(true);

        String oldRefresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");

        BizException ex = assertThrows(BizException.class,
                () -> service.validateAndRotate(oldRefresh));
        assertEquals(401, ex.getCode());
        // Store must NOT be touched (黑名单优先短路)
        verify(refreshStore, never()).isActive(anyString(), anyString());
        verify(refreshStore, never()).revoke(anyString(), anyString());
        verify(refreshStore, never()).store(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("validateAndRotate: signature failure → 401 without touching store")
    void signatureFailureRejects() {
        // A token signed with a DIFFERENT secret is structurally valid but won't
        // verify against our provider — JwtException → 401.
        JwtTokenProvider other = new JwtTokenProvider(
                "b3RoZXItc2VjcmV0LWtleS1ub3QtZXF1YWwtdG8tcHJpbWFyeS1mb3ItdGVzdC1vbmx5",
                ACCESS_TTL, REFRESH_TTL);
        String forged = other.generateRefreshToken("u-1", "alice");

        assertThrows(BizException.class, () -> service.validateAndRotate(forged));
        verify(refreshStore, never()).isActive(anyString(), anyString());
        verify(refreshStore, never()).store(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("validateAndRotate: access token presented instead of refresh → 401")
    void wrongTokenTypeRejected() {
        // generateAccessToken produces typ="access"; validateRefreshToken must reject.
        String accessToken = jwtTokenProvider.generateAccessToken("u-1", "alice", "USER");

        BizException ex = assertThrows(BizException.class,
                () -> service.validateAndRotate(accessToken));
        assertEquals(401, ex.getCode());
        verify(refreshStore, never()).isActive(anyString(), anyString());
    }

    @Test
    @DisplayName("validateAndRotate: blank token → 401 immediately, store untouched")
    void blankTokenRejected() {
        assertThrows(BizException.class, () -> service.validateAndRotate(""));
        assertThrows(BizException.class, () -> service.validateAndRotate(null));
        // store must not be touched on blank inputs
        verify(refreshStore, never()).isActive(anyString(), anyString());
        verify(refreshStore, never()).store(anyString(), anyString(), anyLong());
        verify(refreshStore, never()).revoke(anyString(), anyString());
    }

    @Test
    @DisplayName("validateAndRotate: jti not in active store → 401 (already revoked)")
    void missingJtiInStoreRejected() {
        when(refreshStore.isActive(eq("u-1"), anyString())).thenReturn(false);
        String oldRefresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");

        BizException ex = assertThrows(BizException.class,
                () -> service.validateAndRotate(oldRefresh));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().toLowerCase().contains("revoked"));
    }

    @Test
    @DisplayName("validateAndRotate: user deleted → orphan refresh jti gets cleaned up, 401 returned")
    void orphanRefreshGetsCleaned() {
        when(refreshStore.isActive(eq("u-ghost"), anyString())).thenReturn(true);
        when(userRepository.findById("u-ghost")).thenReturn(Optional.empty());

        String oldRefresh = jwtTokenProvider.generateRefreshToken("u-ghost", "alice");

        assertThrows(BizException.class, () -> service.validateAndRotate(oldRefresh));
        verify(refreshStore).revoke(eq("u-ghost"), anyString());
    }

    // -----------------------------------------------------------------------
    // revokeAllForUser (R14 task 5.1)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("revokeAllForUser: kicks every refresh token for the user; subsequent rotation fails")
    void revokeAllKicksAllDevices() {
        User user = userWithRole("u-1", "alice", "USER");
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(refreshStore.revokeAllForUser("u-1")).thenReturn(3L);

        long revoked = service.revokeAllForUser("u-1");

        assertEquals(3L, revoked);
        verify(refreshStore).revokeAllForUser("u-1");

        // After mass-revoke, any subsequent rotation attempt must fail because
        // store.isActive() will return false (simulating "all jtis deleted").
        when(refreshStore.isActive(eq("u-1"), anyString())).thenReturn(false);
        String r = jwtTokenProvider.generateRefreshToken("u-1", "alice");
        assertThrows(BizException.class, () -> service.validateAndRotate(r));
    }

    @Test
    @DisplayName("revokeAllForUser(userId, accessToken): also writes current access to blacklist")
    void revokeAllWithAccessAlsoRevokesAccess() {
        User user = userWithRole("u-1", "alice", "USER");
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(refreshStore.revokeAllForUser("u-1")).thenReturn(2L);

        String accessToken = jwtTokenProvider.generateAccessToken("u-1", "alice", "USER");
        long revoked = service.revokeAllForUser("u-1", accessToken);

        assertEquals(2L, revoked);
        String accessJti = jwtTokenProvider.getJti(accessToken);
        // TTL 是"剩余有效期"，两个 token mint 出来到调用 revoke 之间会差几毫秒；用 anyLong() 匹配
        verify(tokenBlacklistService).revoke(eq(accessJti), anyLong());
    }

    @Test
    @DisplayName("revokeAllForUser(userId, accessToken): blank access token is silently skipped")
    void revokeAllWithBlankAccessSkips() {
        when(refreshStore.revokeAllForUser("u-1")).thenReturn(0L);
        long revoked = service.revokeAllForUser("u-1", "");
        assertEquals(0L, revoked);
        verify(tokenBlacklistService, never()).revoke(anyString(), anyLong());
    }

    @Test
    @DisplayName("revokeAllForUser: blank userId → 400")
    void revokeAllBlankUserId() {
        assertThrows(BizException.class, () -> service.revokeAllForUser(""));
        verify(refreshStore, never()).revokeAllForUser(anyString());
    }

    // -----------------------------------------------------------------------
    // sanity: rotation emits a fresh access token with the right claims
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("validateAndRotate: rotated access token has fresh jti and correct claims")
    void rotatedAccessHasFreshJtiAndClaims() {
        User user = userWithRole("u-1", "alice", "USER");
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(refreshStore.isActive(eq("u-1"), anyString())).thenReturn(true);

        String oldRefresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");
        String oldRefreshJti = jwtTokenProvider.getJti(oldRefresh);

        JwtRefreshService.RotationResult result = service.validateAndRotate(oldRefresh);

        Claims newAccess = jwtTokenProvider.validateToken(result.authResponse().getAccessToken());
        assertEquals("u-1", newAccess.getSubject());
        assertEquals("alice", newAccess.get("username", String.class));
        assertEquals("USER", newAccess.get("role", String.class));
        assertNotEquals(oldRefreshJti, newAccess.getId(),
                "rotated access token must have its own fresh jti");
        assertEquals("access", newAccess.get("typ", String.class),
                "rotated access token must carry typ=access for downstream filter");

        Claims newRefresh = jwtTokenProvider.validateToken(result.authResponse().getRefreshToken());
        assertEquals("refresh", newRefresh.get("typ", String.class));
    }
}