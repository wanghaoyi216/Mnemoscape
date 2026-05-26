package com.mnemoscape.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public class JwtTokenProvider {

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

    public String generateAccessToken(String userId, String username) {
        return buildToken(userId, username, accessTokenExpiration);
    }

    public String generateRefreshToken(String userId, String username) {
        return buildToken(userId, username, refreshTokenExpiration);
    }

    private String buildToken(String userId, String username, long expiration) {
        Date now = new Date();
        // jti = JWT ID。每次签发都给一个 UUID，让黑名单可以按 token 维度精确撤销
        // （而不是粗暴的把这个用户所有 token 全部踢掉）。
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim("username", username)
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
        } catch (JwtException e) {
            throw new JwtException("Invalid or expired token", e);
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
