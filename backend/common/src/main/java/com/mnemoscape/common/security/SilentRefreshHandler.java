package com.mnemoscape.common.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * 401 静默刷新扩展点。JwtAuthFilter 在检测到 access token 已过期时，会回调本接口
 * 尝试使用 refresh token 重新颁发一对新 token。调用方（auth-service）可以自行
 * 实现缓存、Redis、JWT 黑名单等具体策略；common 模块只负责"触发"。
 *
 * <p>典型用法：客户端在 {@code Authorization: Bearer <access>} 之外，再通过
 * {@code X-Refresh-Token} header 携带 refresh token。access 过期但 refresh
 * 仍有效时，过滤器自动完成 rotation 并把新 token 写回 response header（参见
 * {@link com.mnemoscape.common.security.JwtAuthFilter} 中的
 * {@code X-New-Access-Token} / {@code X-New-Refresh-Token}），业务代码无感知。
 *
 * <p>如果返回 {@link Optional#empty()} 表示刷新失败，filter 仍会回退到 401 响应。
 *
 * <p>线程模型：实现类必须线程安全 —— Filter 在 Tomcat worker 线程上被并发调用。
 */
@FunctionalInterface
public interface SilentRefreshHandler {

    /**
     * 尝试用 refresh token 换发新 token。
     *
     * @param request       当前 HTTP 请求，用于读取 refresh token（约定 header：{@code X-Refresh-Token}）
     * @param expiredAccess 已过期的 access token 原文（仍可用于解析 subject / jti 等元数据）
     * @return 成功则返回包含新 access / refresh token 的 RefreshedTokens；失败或无 refresh 可用返回 {@link Optional#empty()}
     */
    Optional<RefreshedTokens> tryRefresh(HttpServletRequest request, String expiredAccess);

    /**
     * 静默刷新的产物。字段语义：
     * <ul>
     *   <li>{@code userId} / {@code username} / {@code role} —— 用于在 SecurityContext 里
     *       重建认证主体，让下游控制器/服务无感继续处理。</li>
     *   <li>{@code accessToken} / {@code refreshToken} —— 已完成 rotation 的新一对 token，
     *       JwtAuthFilter 会把它们写到 {@code X-New-Access-Token} / {@code X-New-Refresh-Token}
     *       response header，让客户端透明替换本地存储。</li>
     * </ul>
     */
    record RefreshedTokens(String userId,
                           String username,
                           String role,
                           String accessToken,
                           String refreshToken) {
    }
}