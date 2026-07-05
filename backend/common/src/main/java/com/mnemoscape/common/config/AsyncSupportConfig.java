package com.mnemoscape.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 异步线程池监控基类，便于在运行时观察各服务的 @Async 线程池表现。
 *
 * <p>这里不强制开启 @EnableAsync（它必须在主配置类或自动配置类上才稳定生效，
 * common 模块的 @Configuration 在被各服务 scanBasePackages 扫描时也能织入，
 * 但为避免重复开启和顺序问题，统一开关仍由各服务的 Application 类持有）。
 *
 * <p>本类存在的意义：作为后续接入 ThreadPool 监控指标（actuator metrics）
 * 的挂载点。当前保留为空配置，确保 common 模块下 future 扩展有归属包。
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor")
public class AsyncSupportConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncSupportConfig.class);

    @PostConstruct
    void logInit() {
        log.debug("[common] AsyncSupportConfig loaded — @Async 标签在各服务 Application 已统一开启");
    }
}
