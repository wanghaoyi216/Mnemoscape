package com.mnemoscape.common.security;

/**
 * Token 黑名单查询契约。
 *
 * <p>放在 common 里是为了让 {@link JwtAuthFilter} 在不依赖具体存储实现的前提下
 * 可被注入（auth-service 接 Redis、网关接 reactive Redis、单测接内存 Map）。
 * 默认实现 {@link #NOOP} 永远返回 false，相当于未启用黑名单 —— 这样老服务不
 * 引 Redis 也能继续编译运行。
 */
public interface JwtBlacklist {

    boolean isBlacklisted(String jti);

    JwtBlacklist NOOP = jti -> false;
}
