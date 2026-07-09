package com.mnemoscape.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式装配 {@link RedissonClient}，供 {@code DistributedLockStore} 等组件使用。
 *
 * <p>读 {@code spring.data.redis.*} 标准属性，与各服务的 Lettuce 连接共用同一 Redis 实例。
 * {@link ConditionalOnMissingBean} 保证不与 redisson-spring-boot-starter 自带的 auto-config
 * 冲突 —— 若 starter 已注册 RedissonClient 则此处退让；若 starter 未生效则此处补齐，
 * 确保分布式锁不会因 auto-config 行为不确定而 fail-open。
 */
@Configuration
@ConditionalOnClass(RedissonClient.class)
public class RedissonAutoConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        var single = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8);
        if (password != null && !password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
