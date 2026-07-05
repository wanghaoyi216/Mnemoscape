package com.mnemoscape.common.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用 Redis 连接优化参数说明类（仅声明，不强制创建 Bean）。
 *
 * <p><b>重要</b>：原先这里尝试提供一个自定义的 LettuceConnectionFactory Bean，
 * 但在多服务场景下会与 Spring Boot 的 RedisAutoConfiguration 冲突（servlet 栈
 * 和 reactive 栈各需要一个不同的 Factory 包装），导致Bean 覆盖告警或启动失败。
 *
 * <p>真正生效的高并发优化参数在每个服务的 application.yml 的
 * {@code spring.data.redis.lettuce.pool} + {@code spring.data.redis.timeout} 已配置：
 * <ul>
 *   <li>max-active 从 16 提到 50</li>
 *   <li>开启 TCP keepAlive（通过 application.yml 的 lettuce.client-options 不可配，
 *       这里保留类作为将来通过 BeanPostProcessor 统一注入的挂载点）</li>
 * </ul>
 *
 * <p>此类留作后续扩展用途，当前实现为占位，避免模块空包。
 */
@Configuration
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
public class RedisConnectionConfig {

    // 当前所有 Redis 高并发优化通过 application.yml 的 lettuce.pool 配置实现。
    // 如未来需要在代码层统一注入 ClientOptions（keepAlive 等），可在此添加
    // 一个 BeanPostProcessor 对 LettuceConnectionFactory 进行 postProcess。

    @Value("${spring.data.redis.host:127.0.0.1}")
    protected String host;

    @Value("${spring.data.redis.port:6379}")
    protected int port;
}
