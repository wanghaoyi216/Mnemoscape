package com.mnemoscape.auth.service;

import com.mnemoscape.auth.model.dto.AuthResponse;
import com.mnemoscape.auth.model.dto.LoginRequest;
import com.mnemoscape.auth.model.dto.RegisterRequest;
import com.mnemoscape.auth.model.dto.UserProfileResponse;
import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.auth.security.RedisJwtBlacklist;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final RedisJwtBlacklist blacklist;

    @Value("${mnemoscape.jwt.access-token-expiration}")
    private long accessTokenExpiration;

    public AuthService(UserRepository userRepository,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder,
                       RedisJwtBlacklist blacklist) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.blacklist = blacklist;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.getUsername());
        String email = normalizeEmail(request.getEmail());

        if (userRepository.existsByUsername(username)) {
            throw new BizException(409, "Username already exists");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BizException(409, "Email already registered");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();
        userRepository.save(user);

        log.info("User registered username={} email-hash={}", user.getUsername(), maskEmail(user.getEmail()));
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String username = normalizeUsername(request.getUsername());
        // Constant-message error to avoid email/username enumeration.
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BizException(401, "Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Failed login attempt username={}", username);
            throw new BizException(401, "Invalid credentials");
        }

        log.info("User logged in username={}", user.getUsername());
        return buildAuthResponse(user);
    }

    public AuthResponse refreshToken(String refreshToken) {
        try {
            var claims = jwtTokenProvider.validateToken(refreshToken);
            String userId = claims.getSubject();
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BizException(401, "User not found"));
            return buildAuthResponse(user);
        } catch (BizException biz) {
            throw biz;
        } catch (Exception e) {
            log.debug("Refresh token rejected reason={}", e.getClass().getSimpleName());
            throw new BizException(401, "Invalid refresh token");
        }
    }

    /**
     * 登出。把当前 access token 的 jti 写入 Redis 黑名单，并按剩余有效期设置 TTL。
     * Refresh token 暂未额外撤销 — 前端会同步丢弃；想强化可在请求体里带 refresh
     * 一起撤销，但那需要客户端做调整，留给后续 sprint。
     */
    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            String jti = jwtTokenProvider.getJti(accessToken);
            long ttl = jwtTokenProvider.getRemainingMillis(accessToken);
            if (jti == null || ttl <= 0) {
                log.debug("Logout no-op: jti or ttl missing for token");
                return;
            }
            blacklist.revoke(jti, ttl);
            log.info("Logout recorded jti={} ttlMs={}", jti, ttl);
        } catch (Exception e) {
            // 即便 Redis 不可达，前端清掉本地 token 也算"登出"；不要把异常吐回去
            log.warn("Logout encountered an error but client-side sign-out should still proceed: {}",
                    e.getClass().getSimpleName());
        }
    }

    public UserProfileResponse getProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            throw BizException.unauthorized();
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("User", userId));
        return toProfileResponse(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(String userId, String avatarUrl, String backgroundImageUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("User", userId));
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl.trim().isEmpty() ? null : avatarUrl.trim());
        }
        if (backgroundImageUrl != null) {
            user.setBackgroundImageUrl(backgroundImageUrl.trim().isEmpty() ? null : backgroundImageUrl.trim());
        }
        userRepository.save(user);
        log.info("User profile updated userId={}", userId);
        return toProfileResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());
        return AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessTokenExpiration)
                .build();
    }

    private UserProfileResponse toProfileResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getBackgroundImageUrl(),
                user.getVerified(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            throw BizException.badRequest("Username is required");
        }
        return username.trim();
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            throw BizException.badRequest("Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Avoid leaking raw email into logs. */
    private String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
