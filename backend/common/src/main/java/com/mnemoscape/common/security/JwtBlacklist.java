package com.mnemoscape.common.security;

/**
 * Token 黑名单查询契约。
 *
 * <p>放在 common 里是为了让 {@link JwtAuthFilter} 在不依赖具体存储实现的前提下
 * 可被注入（auth-service 接 Redis、网关接 reactive Redis、单测接内存 Map）。
 * 默认实现 {@link #NOOP} 永远返回 false，相当于未启用黑名单 —— 这样老服务不
 * 引 Redis 也能继续编译运行。
 *
 * <p>黑名单空间分两段：
 * <ul>
 *   <li>{@link #isBlacklisted(String)} —— access token 的 jti；被 logout
 *       显式撤销后写入 {@code jwt:blacklist:{jti}}。</li>
 *   <li>{@link #isRefreshBlacklisted(String)} —— refresh token 的 jti；
 *       在 refresh 轮换成功时把旧 refresh 的 jti 写入
 *       {@code jwt:blacklist:refresh:{jti}}，TTL 与 token 剩余有效期一致。</li>
 * </ul>
 * 两套空间独立查询，避免 logout 把所有 refresh 误伤。
 */
public interface JwtBlacklist {

    /** 判定 access token 的 jti 是否被撤销（logout / 管理员踢人）。 */
    boolean isBlacklisted(String jti);

    /** 判定 refresh token 的 jti 是否被撤销（轮换 / 强制下线）。 */
    boolean isRefreshBlacklisted(String jti);

    /** 不启用黑名单的占位实现，null-safe。 */
    JwtBlacklist NOOP = new JwtBlacklist() {
        @Override public boolean isBlacklisted(String jti) { return false; }
        @Override public boolean isRefreshBlacklisted(String jti) { return false; }
    };
}
