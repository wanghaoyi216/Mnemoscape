package com.mnemoscape.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ai-service 启动类。
 *
 * <p>{@link EnableAspectJAutoProxy} 显式开启 AOP，让
 * {@code com.mnemoscape.ai.tools.audit.ToolAuditAspect} 能拦截
 * {@code @Tool} 注解的工具方法。spring-boot-starter-aop 已经在 common 模块
 * 通过传递依赖引入；这里加注解即可生效。
 *
 * <p>{@link EnableScheduling} 开启 Spring 调度能力，给
 * {@link com.mnemoscape.ai.index.IndexRebuildService#scheduledRebuild()} 的
 * {@code @Scheduled(cron=...)} 兜底重建提供运行时。R19。
 */
@SpringBootApplication(scanBasePackages = {"com.mnemoscape.ai", "com.mnemoscape.common"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableAsync
@EnableScheduling
@EnableAspectJAutoProxy
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
