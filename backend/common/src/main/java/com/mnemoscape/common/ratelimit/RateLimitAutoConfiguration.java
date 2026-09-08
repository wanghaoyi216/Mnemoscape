package com.mnemoscape.common.ratelimit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * 把 {@link RateLimiter} / {@link RateLimitAspect} 显式注册到 Spring 容器。
 *
 * <p>这两个 bean 都在 common 模块里，<b>不在</b>各 service 的
 * {@code @SpringBootApplication} 默认扫描路径下（{@code com.mnemoscape.<svc>}
 * 及其子包）。普通 {@code @Component} 不会被自动发现。
 *
 * <p>通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 显式引入 —— Spring Boot 3.x 的 auto-configuration 机制 in 所有 service
 * 启动时自动加载它，等同于把 rate limit 的"零件"在 service 类路径里"打补丁"。
 *
 * <p>不抢 {@code @Primary CacheManager} 等关键 bean 名，避免和业务配置冲突。
 */
@AutoConfiguration
@ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
public class RateLimitAutoConfiguration {

    @Bean
    public RateLimiter rateLimiter(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            org.springframework.data.redis.core.StringRedisTemplate redis) {
        // 找不到 StringRedisTemplate 时回退到"全放行"的实现 —— Redis 不可用
        // 或者 service 根本没引 redis 依赖都不会让启动失败。
        if (redis == null) {
            return (bucket, limit, window) -> RateLimiter.Decision.failOpen();
        }
        return new RedisSlidingWindowRateLimiter(redis);
    }

    @Bean
    public RateLimitAspect rateLimitAspect(RateLimiter rateLimiter) {
        return new RateLimitAspect(rateLimiter);
    }
}
