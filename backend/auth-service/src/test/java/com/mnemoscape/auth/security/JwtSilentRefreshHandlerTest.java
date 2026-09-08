package com.mnemoscape.auth.security;

import com.mnemoscape.auth.model.dto.AuthResponse;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.security.JwtAuthFilter;
import com.mnemoscape.common.security.JwtTokenProvider;
import com.mnemoscape.common.security.SilentRefreshHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JwtSilentRefreshHandler} — R14 task 5.1 静默刷新入口。
 *
 * <p>覆盖：
 * <ul>
 *   <li>refresh header 缺失 → empty（filter 走 401）。</li>
 *   <li>refresh header 空白 → empty。</li>
 *   <li>refresh rotation 成功 → 返回 RefreshedTokens。</li>
 *   <li>refresh rotation 失败（401/500/异常） → empty（filter 走 401）。</li>
 *   <li>user mismatch（access 是 A、refresh 是 B） → empty，挡掉 subject 错配。</li>
 * </ul>
 */
class JwtSilentRefreshHandlerTest {

    private static final String SECRET =
            "bW5lbW9zY2FwZS1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni1hbGdvcml0aG0tMjAyNQ==";

    private JwtRefreshService refreshService;
    private JwtTokenProvider jwtTokenProvider;
    private JwtSilentRefreshHandler handler;

    @BeforeEach
    void setUp() {
        refreshService = mock(JwtRefreshService.class);
        jwtTokenProvider = new JwtTokenProvider(SECRET, 3_600_000L, 86_400_000L);
        handler = new JwtSilentRefreshHandler(refreshService, jwtTokenProvider);
    }

    @Test
    @DisplayName("tryRefresh: missing X-Refresh-Token header → empty without calling service")
    void missingRefreshHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(JwtAuthFilter.REFRESH_HEADER)).thenReturn(null);

        Optional<SilentRefreshHandler.RefreshedTokens> out = handler.tryRefresh(req, "any-access");

        assertFalse(out.isPresent());
        verify(refreshService, never()).validateAndRotate(anyString());
    }

    @Test
    @DisplayName("tryRefresh: blank X-Refresh-Token → empty")
    void blankRefreshHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(JwtAuthFilter.REFRESH_HEADER)).thenReturn("   ");

        Optional<SilentRefreshHandler.RefreshedTokens> out = handler.tryRefresh(req, "any-access");

        assertFalse(out.isPresent());
        verify(refreshService, never()).validateAndRotate(anyString());
    }

    @Test
    @DisplayName("tryRefresh: rotation success → returns userId / username / role / new access / new refresh")
    void rotationSuccessReturnsTokens() {
        // Mint a valid refresh token for u-1 so the request header has structure.
        String refresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(JwtAuthFilter.REFRESH_HEADER)).thenReturn(refresh);

        // Stub the service to claim successful rotation
        String newAccess = jwtTokenProvider.generateAccessToken("u-1", "alice", "USER");
        String newRefresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");
        AuthResponse auth = AuthResponse.builder()
                .userId("u-1").username("alice")
                .accessToken(newAccess).refreshToken(newRefresh)
                .expiresIn(3_600_000L).role("USER").build();
        when(refreshService.validateAndRotate(refresh))
                .thenReturn(new JwtRefreshService.RotationResult(auth, "u-1", "alice", "USER"));

        // Expired access — provider's getUserId should still recover the subject.
        String expiredAccess = jwtTokenProvider.generateAccessToken("u-1", "alice", "USER");

        Optional<SilentRefreshHandler.RefreshedTokens> out = handler.tryRefresh(req, expiredAccess);

        assertTrue(out.isPresent());
        SilentRefreshHandler.RefreshedTokens rt = out.get();
        assertEquals("u-1", rt.userId());
        assertEquals("alice", rt.username());
        assertEquals("USER", rt.role());
        assertEquals(newAccess, rt.accessToken());
        assertEquals(newRefresh, rt.refreshToken());
    }

    @Test
    @DisplayName("tryRefresh: rotation fails with BizException → empty (no leak of error detail)")
    void rotationFailureMapsToEmpty() {
        String refresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(JwtAuthFilter.REFRESH_HEADER)).thenReturn(refresh);
        when(refreshService.validateAndRotate(refresh))
                .thenThrow(new BizException(401, "Refresh token has been revoked"));

        Optional<SilentRefreshHandler.RefreshedTokens> out =
                handler.tryRefresh(req, "any-access");

        assertFalse(out.isPresent(), "biz errors must surface as empty so the filter writes 401");
    }

    @Test
    @DisplayName("tryRefresh: rotation throws RuntimeException → empty (Redis down / DB hiccup)")
    void rotationRuntimeExceptionMapsToEmpty() {
        String refresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(JwtAuthFilter.REFRESH_HEADER)).thenReturn(refresh);
        when(refreshService.validateAndRotate(refresh))
                .thenThrow(new RuntimeException("Redis connection refused"));

        Optional<SilentRefreshHandler.RefreshedTokens> out =
                handler.tryRefresh(req, "any-access");

        assertFalse(out.isPresent());
    }

    @Test
    @DisplayName("tryRefresh: subject mismatch (access=u-1, rotated-for=u-2) → empty")
    void subjectMismatchRejects() {
        String refresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(JwtAuthFilter.REFRESH_HEADER)).thenReturn(refresh);

        // Simulate: rotation service claims the user is u-2 (somehow). The expired
        // access belongs to u-1. Must be rejected.
        AuthResponse auth = AuthResponse.builder()
                .userId("u-2").username("eve")
                .accessToken("a").refreshToken("r")
                .expiresIn(3_600_000L).role("USER").build();
        when(refreshService.validateAndRotate(refresh))
                .thenReturn(new JwtRefreshService.RotationResult(auth, "u-2", "eve", "USER"));

        String expiredAccess = jwtTokenProvider.generateAccessToken("u-1", "alice", "USER");

        Optional<SilentRefreshHandler.RefreshedTokens> out = handler.tryRefresh(req, expiredAccess);
        assertFalse(out.isPresent(),
                "subject mismatch between expired access and rotated refresh must NOT mint new tokens");
    }

    @Test
    @DisplayName("tryRefresh: garbage access header → still rotates (subject can't be parsed)")
    void garbageAccessStillRotates() {
        // Access token is unparseable — getUserId will throw. Handler must catch and
        // continue with the refresh's own subject.
        String refresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(JwtAuthFilter.REFRESH_HEADER)).thenReturn(refresh);

        AuthResponse auth = AuthResponse.builder()
                .userId("u-1").username("alice")
                .accessToken("a").refreshToken("r")
                .expiresIn(3_600_000L).role("USER").build();
        when(refreshService.validateAndRotate(refresh))
                .thenReturn(new JwtRefreshService.RotationResult(auth, "u-1", "alice", "USER"));

        // expiredAccess is unparseable garbage; the handler must not crash.
        Optional<SilentRefreshHandler.RefreshedTokens> out =
                handler.tryRefresh(req, "garbage-not-a-jwt");

        assertTrue(out.isPresent(), "garbage expired access must not poison the refresh path");
        assertEquals("u-1", out.get().userId());
    }

    @Test
    @DisplayName("tryRefresh: rotated access token must be non-null / non-blank (smoke)")
    void rotatedAccessIsUsable() {
        String refresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");
        String newAccess = jwtTokenProvider.generateAccessToken("u-1", "alice", "USER");
        String newRefresh = jwtTokenProvider.generateRefreshToken("u-1", "alice");

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(JwtAuthFilter.REFRESH_HEADER)).thenReturn(refresh);
        AuthResponse auth = AuthResponse.builder()
                .userId("u-1").username("alice")
                .accessToken(newAccess).refreshToken(newRefresh)
                .expiresIn(3_600_000L).role("USER").build();
        when(refreshService.validateAndRotate(refresh))
                .thenReturn(new JwtRefreshService.RotationResult(auth, "u-1", "alice", "USER"));

        Optional<SilentRefreshHandler.RefreshedTokens> out =
                handler.tryRefresh(req, jwtTokenProvider.generateAccessToken("u-1", "alice", "USER"));

        assertTrue(out.isPresent());
        assertNotNull(out.get().accessToken());
        assertNotNull(out.get().refreshToken());
        // And the rotated access token must decode correctly
        assertEquals("u-1", jwtTokenProvider.getUserId(out.get().accessToken()));
    }
}