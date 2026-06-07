package com.mnemoscape.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * {@link RateLimit} 注解的 AOP 织入 —— 把限流检查织到方法调用前。
 *
 * <p>职责：
 * <ol>
 *   <li>从 {@link RateLimit} 读 limit / window / dimension 配置</li>
 *   <li>根据 dimension 解析"限流主体"：USER → {@code auth.getName()}，
 *       IP → {@code X-Forwarded-For} → {@code remoteAddr}，
 *       GLOBAL → 空字符串</li>
 *   <li>调 {@link RateLimiter#tryAcquire} 检查</li>
 *   <li>被拒 → 抛 {@link RateLimitExceededException}（GlobalExceptionHandler → 429）</li>
 *   <li>放行 → proceed</li>
 * </ol>
 *
 * <p>顺序：{@code @Order(Ordered.HIGHEST_PRECEDENCE + 100)} —— 比
 * {@code @Transactional} / {@code @Cacheable} 更外层，避免 DB / Cache 已消耗后才
 * 限流；同时比 controller 入口的鉴权 / MDC filter 更内层，让已鉴权主体在 limit
 * 计算里被识别。
 */
@Aspect
@Order(0)
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final RateLimiter rateLimiter;

    public RateLimitAspect(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Around("@annotation(com.mnemoscape.common.ratelimit.RateLimit) || @within(com.mnemoscape.common.ratelimit.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        RateLimit cfg = resolveConfig(pjp);
        if (cfg == null) {
            // pointcut 已保证注解存在 —— 这里是防御性兜底，正常不会触发
            return pjp.proceed();
        }
        String subject = resolveSubject(cfg.dimension());
        String bucket = (cfg.key().isEmpty()
                ? pjp.getSignature().toShortString()
                : cfg.key()) + ":" + subject;
        RateLimiter.Decision decision = rateLimiter.tryAcquire(bucket, cfg.limit(), cfg.windowSeconds());
        if (decision.allowed()) {
            if (decision.degraded()) {
                log.debug("[ratelimit] degraded pass bucket={} subject={}", cfg.key(), subject);
            }
            return pjp.proceed();
        }
        log.info("[ratelimit] denied bucket={} subject={} retryAfter={}s",
                cfg.key(), subject, decision.retryAfterSeconds());
        throw new RateLimitExceededException(bucket, decision.retryAfterSeconds(), cfg.message());
    }

    /**
     * 用反射查注解 —— 不依赖 pointcut 的参数绑定。
     *
     * <p>原因：Spring AOP 的 pointcut 参数绑定在联合表达式里很容易产生
     * null 或未绑定形参问题。这里的 advice 不接收 {@link RateLimit} 参数，
     * pointcut 只负责匹配，具体配置统一从最具体的目标方法 / 类上读取。
     *
     * <p>类级 + 方法级都有时，方法级优先（更具体）。
     */
    private RateLimit resolveConfig(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        Class<?> targetClass = pjp.getTarget() == null
                ? method.getDeclaringClass()
                : AopUtils.getTargetClass(pjp.getTarget());
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

        RateLimit onMethod = specificMethod.getAnnotation(RateLimit.class);
        if (onMethod != null) {
            return onMethod;
        }
        if (!specificMethod.equals(method)) {
            onMethod = method.getAnnotation(RateLimit.class);
            if (onMethod != null) {
                return onMethod;
            }
        }

        RateLimit onClass = targetClass.getAnnotation(RateLimit.class);
        if (onClass != null) {
            return onClass;
        }
        Class<?> declaring = specificMethod.getDeclaringClass();
        onClass = declaring.getAnnotation(RateLimit.class);
        if (onClass != null) {
            return onClass;
        }
        // 处理内部类场景 —— 外层类上的注解
        Class<?> enclosing = declaring.getEnclosingClass();
        return enclosing == null ? null : enclosing.getAnnotation(RateLimit.class);
    }

    private String resolveSubject(RateLimit.Dimension dim) {
        if (dim == RateLimit.Dimension.GLOBAL) {
            return "GLOBAL";
        }
        if (dim == RateLimit.Dimension.USER || dim == RateLimit.Dimension.USER_OR_IP) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getName() != null
                    && !"anonymousUser".equals(auth.getName())) {
                return "u:" + auth.getName();
            }
            if (dim == RateLimit.Dimension.USER) {
                return "u:anon-" + clientIp();
            }
        }
        return "ip:" + clientIp();
    }

    private String clientIp() {
        HttpServletRequest req = currentRequest();
        if (req == null) return "0.0.0.0";
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr() == null ? "0.0.0.0" : req.getRemoteAddr();
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
    }
}
