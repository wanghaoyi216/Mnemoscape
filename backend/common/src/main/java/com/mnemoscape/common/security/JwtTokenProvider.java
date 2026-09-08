package com.mnemoscape.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public class JwtTokenProvider {

    /** R6 安全修复：token 类型 claim。access / refresh 必须严格隔离，防 refresh 冒充 access。 */
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final String CLAIM_TYP = "typ";

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtTokenProvider(String secret, long accessTokenExpiration, long refreshTokenExpiration) {
        byte[] keyBytes = Base64.getDecoder().decode(
                Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8))
        );
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    /**
     * 旧签名：向后兼容入口。等价于 {@code generateAccessToken(userId, username, "USER")}。
     * 新代码请使用三参数重载以显式传入角色。
     */
    public String generateAccessToken(String userId, String username) {
        return generateAccessToken(userId, username, "USER");
    }

    /**
     * 颁发携带 {@code role} claim 的 access token。
     * <p>
     * 角色值会被规范化：仅当传入的字符串严格等于 {@code "ADMIN"}（case-sensitive）时
     * 写入 {@code "ADMIN"}，其余一切情况（包括 null、空、小写、未知值）一律写入 {@code "USER"}。
     * 这样下游的 {@code JwtAuthFilter} 始终能拿到合法、可信的角色字面量。
     *
     * @param userId   JWT subject
     * @param username JWT username claim
     * @param role     期望写入的角色，目前仅 {@code "ADMIN"} / {@code "USER"} 两值
     */
    public String generateAccessToken(String userId, String username, String role) {
        return buildToken(userId, username, role, accessTokenExpiration, TOKEN_TYPE_ACCESS);
    }

    public String generateRefreshToken(String userId, String username) {
        // refresh token 不直接驱动鉴权，统一写入 USER 即可，避免 claim 缺失导致解析分支失衡。
        return buildToken(userId, username, "USER", refreshTokenExpiration, TOKEN_TYPE_REFRESH);
    }

    private String buildToken(String userId, String username, String role, long expiration) {
        return buildToken(userId, username, role, expiration, TOKEN_TYPE_ACCESS);
    }

    private String buildToken(String userId, String username, String role, long expiration, String typ) {
        Date now = new Date();
        String resolvedRole = "ADMIN".equals(role) ? "ADMIN" : "USER";
        // jti = JWT ID。每次签发都给一个 UUID，让黑名单可以按 token 维度精确撤销
        // （而不是粗暴的把这个用户所有 token 全部踢掉）。
        //
        // R6 安全修复：新增 typ（token type）claim。此前 access 与 refresh token
        // 结构完全相同（同签名密钥、同 claims、仅过期时间不同），导致"轮换即撤销"
        // 的语义可被绕过——被撤销的旧 refresh token 拿去当 access token 用，
        // 网关 validateToken 只查 access 黑名单必然未命中，最长可用 7 天。
        // 现在网关 / JwtAuthFilter 强校验 typ=="access"，refresh 接口强校验 typ=="refresh"。
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim("username", username)
                .claim("role", resolvedRole)
                .claim("typ", typ)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(secretKey)
                .compact();
    }

    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException expired) {
            // R14 静默刷新扩展：必须让 ExpiredJwtException 原样透传，不能被 catch-all 包装。
            // 上游 JwtAuthFilter 看到 ExpiredJwtException 才会触发静默刷新路径；
            // 如果被包装成通用 JwtException，cause chain 检查会复杂且容易漏掉。
            throw expired;
        } catch (JwtException e) {
            throw new JwtException("Invalid or expired token", e);
        }
    }

    /**
     * 校验 refresh token 是否仍然有效（签名 + 未过期 + 未在黑名单）。
     *
     * <p>refresh token 的黑名单空间与 access token 独立（key 前缀见
     * {@code jwt:blacklist:refresh:{jti}}）—— 历史 logout 流程只会撤销
     * access token，refresh token 走"轮换即撤销"语义：每次 refresh 成功后
     * 旧 refresh token 的 jti 被写入黑名单，新 refresh token 才会被签发。
     * 这样即便旧 refresh token 被中间人截获，也无法继续换发新 token。
     *
     * @param token          待校验的 refresh token 字符串
     * @param blacklist      黑名单实现（auth-service 注入了 RedisJwtBlacklist）
     * @return 解析后的 Claims
     * @throws JwtException  签名失败 / 已过期
     * @throws IllegalStateException 当 jti 已被列入黑名单
     */
    public Claims validateRefreshToken(String token, JwtBlacklist blacklist) {
        Claims claims = validateToken(token);
        // R6 安全修复：强校验 typ=="refresh"。防止把 access token 当 refresh 用
        //（或反之），配合 buildToken 写入的 typ claim 实现两类 token 的硬隔离。
        requireType(claims, TOKEN_TYPE_REFRESH);
        String jti = claims.getId();
        if (jti != null && blacklist != null && blacklist.isRefreshBlacklisted(jti)) {
            // 显式 401 区分码，让上层 AuthController.refresh() 知道这是被强制撤销的，
            // 而不是"普通过期"，便于排查撞库 / 重放攻击。
            throw new IllegalStateException("Refresh token has been revoked jti=" + jti);
        }
        return claims;
    }

    /**
     * 校验 token 的 typ claim 是否为期望值。
     *
     * <p>兼容历史 token：R6 之前签发的旧 token 没有 typ claim（值为 null）。
     * 为避免全量用户被强制下线，<b>null 视为 access 放行</b>——旧 refresh token
     * 的自然过期窗口最长 7 天，期间其撤销语义由黑名单兜底；新签发的 token
     * 一律带 typ，冒充路径即被封死。</p>
     *
     * @throws IllegalStateException typ 与期望不符
     */
    public void requireType(Claims claims, String expectedType) {
        String actual = claims.get(CLAIM_TYP, String.class);
        if (actual == null) {
            return; // 历史token：无 typ claim，按 access 处理（见方法注释）
        }
        if (!expectedType.equals(actual)) {
            throw new IllegalStateException(
                    "Invalid token type: expected " + expectedType + " but got " + actual);
        }
    }

    public String getUserId(String token) {
        return validateToken(token).getSubject();
    }

    /** 返回 token 的 jti（JWT ID）。老 token 没有 jti 时返回 null。 */
    public String getJti(String token) {
        return validateToken(token).getId();
    }

    /** 返回 token 还剩多少毫秒过期，已过期返回 0。供黑名单 TTL 使用。 */
    public long getRemainingMillis(String token) {
        Date exp = validateToken(token).getExpiration();
        if (exp == null) return 0L;
        long delta = exp.getTime() - System.currentTimeMillis();
        return Math.max(0L, delta);
    }

    public boolean isTokenValid(String token) {
        try {
            validateToken(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
}
