package com.mnemoscape.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * ai-service 专用阻塞调度器，隔离 LLM / ReAct 等慢路径，避免占用全局
 * {@link Schedulers#boundedElastic()} 拖累其他 reactor 订阅。
 *
 * <p>LLM 调用是秒级阻塞，若复用全局 boundedElastic（默认 10*CPU 核）会被
 * workflow / ReAct 循环占满，导致其它非 AI 路径排队。这里建独立有界弹性池，
 * 线程名前缀 {@code ai-blocking} 便于线程 dump 定位。
 */
@Configuration
public class AiSchedulerConfig {

    @Bean(name = "aiBlockingScheduler")
    public Scheduler aiBlockingScheduler() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Schedulers.newBoundedElastic(
                Math.max(8, cores * 2),
                500,
                "ai-blocking",
                60,
                false);
    }
}
