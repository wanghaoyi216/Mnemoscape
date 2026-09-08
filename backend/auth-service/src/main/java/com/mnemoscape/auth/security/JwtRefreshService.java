package com.mnemoscape.auth.security;

import com.mnemoscape.auth.model.dto.AuthResponse;
import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.security.JwtBlacklist;
import com.mnemoscape.common.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Refresh token 生命周期编排 —— R14 的核心业务门面。
 *
 * <p>三个核心方法对应三种生命周期事件：
 * <ul>
 *   <li>{@link #issueRefreshToken(String)} —— 颁发新 refresh token，写入
 *       {@link RefreshTokenStore}。登录成功、首次注册时调用。</li>
 *   <li>{@link #validateAndRotate(String)} —— 校验旧 refresh token + 自动旋转
 *       （旧 token 立即失效，签发新一对 access + refresh）。客户端调用
 *       {@code POST /api/v1/auth/refresh} 与 JwtAuthFilter 静默刷新都走这里。</li>
 *   <li>{@link #revokeAllForUser(String)} —— 撤销某用户全部 refresh token，
 *       用于"踢出所有设备" / 改密后的兜底清理。</li>
 * </ul>
 *
 * <p><b>轮换语义（rotation）：</b>
 * <pre>
 *   客户端  ───────►  POST /api/v1/auth/refresh  (refresh=A)
 *   服务端  ─── 1. validate(A) : 签名 + 未过期 + jti 在 RefreshTokenStore 白名单
 *            ─── 2. issue access + refresh (B)
 *            ─── 3. DELETE jwt:refresh:{userId}:A   ← 旧 token 立即失效
 *            ─── 4. 返回 (access, refresh=B)
 *   客户端  ───────►  用 B 替换本地 A
 * </pre>
 *
 * <p>如果攻击者截获了 A 并抢先调了 refresh，他会先于真正的客户端拿到 B；之后真正的
 * 客户端拿 A 来 refresh 时，A 已经在白名单里被删，会被判定为"已被撤销" → 401。
 * 这是 R14 防 refresh token 重放的核心机制（参见 R14 需求文档 §2.3）。
 *
 * <p><b>关键不变式：</b>同一时刻一个用户最多有 N 个存活的 refresh token（N = 该用户
 * 当前活跃设备数）。{@link #revokeAllForUser(String)} 可以一键把 N 归零。
 */
@Service
public class JwtRefreshService {

    private static final Logger log = LoggerFactory.getLogger(JwtRefreshService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final UserRepository userRepository;
    private final JwtBlacklist blacklist;
    private final TokenBlacklistService tokenBlacklistService;

    @Value("${mnemoscape.jwt.access-token-expiration}")
    private long accessTokenExpiration;

    public JwtRefreshService(JwtTokenProvider jwtTokenProvider,
                            RefreshTokenStore refreshTokenStore,
                            UserRepository userRepository,
                            JwtBlacklist blacklist,
                            TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.userRepository = userRepository;
        this.blacklist = blacklist;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 为指定用户签发一个新的 refresh token，并把 jti 登记到 RefreshTokenStore。
     *
     * <p>不连带签发 access token —— 登录 / 注册入口直接调
     * {@link com.mnemoscape.auth.service.AuthService#buildAuthResponse} 把 access + refresh
     * 一起返回；这里只负责 refresh 这一半的存储登记，便于静默刷新场景复用。
     *
     * @return 新 refresh token 字符串（JWT 格式）
     */
    @Transactional(readOnly = true)
    public IssuedRefreshToken issueRefreshToken(String userId) {
        if (userId == null || userId.isBlank()) {
            throw BizException.badRequest("userId is required");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("User", userId));

        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());
        String jti;
        long ttlMillis;
        try {
            // generate 出来的 token 一定能解出来，jti 由 buildToken 强制写入
            Claims claims = jwtTokenProvider.validateToken(refreshToken);
            jti = claims.getId();
            ttlMillis = jwtTokenProvider.getRemainingMillis(refreshToken);
        } catch (JwtException e) {
            // 理论上不会发生 —— 刚签的 token 立即校验失败意味着 JwtTokenProvider 出 bug
            log.error("Self-validation of freshly minted refresh token failed: {}",
                    e.getClass().getSimpleName(), e);
            throw new BizException(500, "Failed to mint refresh token", e);
        }

        refreshTokenStore.store(user.getId(), jti, ttlMillis);
        log.info("Refresh token issued userId={} jti={} ttlMs={}",
                user.getId(), jti, ttlMillis);
        return new IssuedRefreshToken(refreshToken, jti, ttlMillis);
    }

    /**
     * 校验 refresh token 并完成 rotation：旧 token 立即失效，签发新一对 token。
     *
     * <p>校验顺序故意做成"宽进严出"：先放宽到只检签名 + 过期 + 白名单存在，
     * 通过后再做 userId 一致性 / 用户存在性等更严格的检查。这样可以
     * 区分"伪造 token"（签名失败）和"已撤销 token"（白名单缺失），便于排查。
     *
     * @return 新一对 access + refresh token，连同 userId / username / role
     * @throws BizException 401 当 refresh token 无效 / 已撤销 / 用户不存在
     */
    @Transactional
    public RotationResult validateAndRotate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(401, "Invalid refresh token");
        }
        Claims claims;
        try {
            // 强校验 typ="refresh" —— 防止把 access token 当 refresh 用，反之亦然
            claims = jwtTokenProvider.validateRefreshToken(refreshToken, blacklist);
        } catch (IllegalStateException typeMismatch) {
            // 类型不符（refresh 接口被错传了 access token）
            log.debug("Refresh token type check failed: {}", typeMismatch.getMessage());
            throw new BizException(401, "Invalid refresh token");
        } catch (JwtException sigOrExpired) {
            log.debug("Refresh signature/expired check failed: {}",
                    sigOrExpired.getClass().getSimpleName());
            throw new BizException(401, "Invalid refresh token");
        }

        String userId = claims.getSubject();
        String jti = claims.getId();
        long remainingTtl = jwtTokenProvider.getRemainingMillis(refreshToken);

        // 白名单二次校验：即使签名通过，也必须 jti 还在 RefreshTokenStore 里
        // —— 这才是 rotation 的关键防线。
        if (jti == null || !refreshTokenStore.isActive(userId, jti)) {
            log.warn("Refresh token jti not in active store userId={} jti={} (likely already rotated)",
                    userId, jti);
            throw new BizException(401, "Refresh token has been revoked");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    // 用户已注销 / 被删，但 refresh token 仍在有效期。
                    // 兜底：把这种孤儿 refresh token 一并清理，避免泄露。
                    refreshTokenStore.revoke(userId, jti);
                    return new BizException(401, "User not found");
                });

        // ---- 通过校验，开始 rotation ----

        // 1. 颁发新一对 token
        String newAccessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getUsername(), user.getRole());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(
                user.getId(), user.getUsername());

        String newJti;
        long newRefreshTtl;
        try {
            Claims newClaims = jwtTokenProvider.validateToken(newRefreshToken);
            newJti = newClaims.getId();
            newRefreshTtl = jwtTokenProvider.getRemainingMillis(newRefreshToken);
        } catch (JwtException e) {
            log.error("Self-validation of new refresh token failed during rotation: {}",
                    e.getClass().getSimpleName(), e);
            throw new BizException(500, "Failed to rotate refresh token", e);
        }

        // 2. 把新 refresh 的 jti 登记到白名单
        refreshTokenStore.store(user.getId(), newJti, newRefreshTtl);

        // 3. 把旧 refresh 的 jti 从白名单删掉 —— 旋转的核心：旧 token 立即失效
        refreshTokenStore.revoke(user.getId(), jti);

        log.info("Refresh token rotated userId={} oldJti={} newJti={}",
                user.getId(), jti, newJti);

        AuthResponse auth = AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(accessTokenExpiration)
                .role(user.getRole())
                .build();

        return new RotationResult(auth, user.getId(), user.getUsername(), user.getRole());
    }

    /**
     * 撤销某用户全部 refresh token。常用于：
     * <ul>
     *   <li>用户主动"登出所有设备"；</li>
     *   <li>管理员强制踢人；</li>
     *   <li>用户修改密码后让旧设备失效。</li>
     * </ul>
     *
     * <p>同时把当前 access token 撤销（如果传入），形成"双保险"：
     * 攻击者即便截获了 access token，也会因为 jti 已上 access 黑名单
     * 在下一次请求被网关 / auth-service 拒绝。
     *
     * @param userId      目标用户 ID
     * @param accessToken 可选；同时撤销的当前 access token（完整原文）
     * @return 实际被撤销的 refresh token 数量
     */
    @Transactional
    public long revokeAllForUser(String userId, String accessToken) {
        if (userId == null || userId.isBlank()) {
            throw BizException.badRequest("userId is required");
        }
        long revoked = refreshTokenStore.revokeAllForUser(userId);

        if (accessToken != null && !accessToken.isBlank()) {
            try {
                String jti = jwtTokenProvider.getJti(accessToken);
                long ttl = jwtTokenProvider.getRemainingMillis(accessToken);
                if (jti != null && !jti.isBlank() && ttl > 0) {
                    // 与 RefreshTokenStore 配合做 access 撤销 —— 写入 jwt:black:{jti}
                    tokenBlacklistService.revoke(jti, ttl);
                }
            } catch (JwtException e) {
                log.debug("Access token revoked-all skipped: {}",
                        e.getClass().getSimpleName());
            }
        }

        log.info("Revoked all refresh tokens for userId={} count={} (also revoked current access)",
                userId, revoked);
        return revoked;
    }

    /** revokeAllForUser 的不带 access token 版本，向后兼容。 */
    public long revokeAllForUser(String userId) {
        return revokeAllForUser(userId, null);
    }

    /**
     * 颁发结果：refresh token 原文 + jti + ttl。供调用方按需再包一层 access token。
     */
    public record IssuedRefreshToken(String refreshToken, String jti, long ttlMillis) {
    }

    /**
     * 旋转结果：除了新一对 token，还带上 userId / username / role —— 静默刷新路径
     * 需要用这三项重建 Spring SecurityContext。
     */
    public record RotationResult(AuthResponse authResponse,
                                 String userId,
                                 String username,
                                 String role) {
    }
}