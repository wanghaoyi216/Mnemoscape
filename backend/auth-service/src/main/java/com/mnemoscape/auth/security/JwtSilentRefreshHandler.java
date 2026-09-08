package com.mnemoscape.auth.security;

import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.security.JwtTokenProvider;
import com.mnemoscape.common.security.SilentRefreshHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * auth-service 对 {@link SilentRefreshHandler} 的具体实现 —— 把 common 模块的
 * "扩展点"接上 R14 的 {@link JwtRefreshService}。
 *
 * <p><b>触发链路：</b>
 * <pre>
 *   client → JwtAuthFilter (access 过期)
 *                  ↓
 *            SilentRefreshHandler.tryRefresh(req, expiredAccess)
 *                  ↓
 *            JwtSilentRefreshHandler ── 从 X-Refresh-Token 拿 refresh
 *                                     ── JwtRefreshService.validateAndRotate()
 *                                     ── 返回 (newAccess, newRefresh, userId, role)
 *                  ↓
 *            JwtAuthFilter 把新 token 写进 X-New-Access-Token / X-New-Refresh-Token
 *            response header，继续走下游过滤器链
 * </pre>
 *
 * <p>关键不变量：silent refresh 路径与 {@code POST /api/v1/auth/refresh} 共享
 * {@link JwtRefreshService#validateAndRotate(String)}，rotation 语义（一次性使用 /
 * 旧 token 立即失效）在两条路径上完全一致 —— 攻击者无法通过"只暴露静默刷新入口"
 * 来绕过 revocation。
 */
@Component
public class JwtSilentRefreshHandler implements SilentRefreshHandler {

    private static final Logger log = LoggerFactory.getLogger(JwtSilentRefreshHandler.class);

    private final JwtRefreshService jwtRefreshService;
    private final JwtTokenProvider jwtTokenProvider;

    public JwtSilentRefreshHandler(JwtRefreshService jwtRefreshService,
                                   JwtTokenProvider jwtTokenProvider) {
        this.jwtRefreshService = jwtRefreshService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Optional<RefreshedTokens> tryRefresh(HttpServletRequest request, String expiredAccess) {
        // 1. 必须带 X-Refresh-Token header。空 / 缺失直接放弃，走 401。
        String refreshToken = request.getHeader(com.mnemoscape.common.security.JwtAuthFilter.REFRESH_HEADER);
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }

        // 2. 可选：从过期 access 里拿 userId 提前对齐 subject，避免"refresh A 用户
        //    被错配到 B 用户"这种诡异 case。实际 JwtRefreshService 内部还会做二次校验，
        //    这里只是早期快速失败 + 日志更精确。
        String expiredUserId = null;
        try {
            expiredUserId = jwtTokenProvider.getUserId(expiredAccess);
        } catch (Exception ignore) {
            // access 过期（ExpiredJwtException）或格式异常 —— 不影响静默刷新，refresh 自带 subject
        }

        // 3. 调 JwtRefreshService.validateAndRotate 走完整的"校验 + 旋转"流程。
        //    失败一律转 Optional.empty()，由过滤器统一回 401。
        try {
            JwtRefreshService.RotationResult result = jwtRefreshService.validateAndRotate(refreshToken);

            // 双保险：refresh 旋转后的 userId 必须等于过期 access 的 userId。
            // 不一致直接拒绝（理论不该发生 —— JwtTokenProvider 要求 sub 一致 —— 但
            // 多一道断言能在 token 类型混淆 / 配置错乱时第一时间发现）。
            if (expiredUserId != null && !expiredUserId.equals(result.userId())) {
                log.warn("Silent refresh user mismatch: expiredAccess={} rotatedFor={}",
                        expiredUserId, result.userId());
                return Optional.empty();
            }

            log.info("Silent refresh succeeded userId={} path={}",
                    result.userId(), request.getRequestURI());

            return Optional.of(new RefreshedTokens(
                    result.userId(),
                    result.username(),
                    result.role(),
                    result.authResponse().getAccessToken(),
                    result.authResponse().getRefreshToken()
            ));
        } catch (BizException biz) {
            // 401/404 等业务异常 —— refresh token 无效 / 用户已注销
            log.debug("Silent refresh rejected code={} reason={} path={}",
                    biz.getCode(), biz.getMessage(), request.getRequestURI());
            return Optional.empty();
        } catch (Exception e) {
            // 兜底：Redis 不可达 / DB 抖动等都返回 empty，让过滤器写 401。
            // 注意是 WARN 级别 —— 这种异常需要 oncall 跟进，但不阻塞主流程。
            log.warn("Silent refresh unexpected error path={} reason={}",
                    request.getRequestURI(), e.getClass().getSimpleName(), e);
            return Optional.empty();
        }
    }
}