package com.mnemoscape.memory.config;

import org.springframework.boot.task.TaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 给 Spring Boot 自动装配的 applicationTaskExecutor 注入 CALLER_RUNS 拒绝策略。
 *
 * <p>线程池的核心 / 最大 / 队列容量仍由 {@code spring.task.execution.pool.*}
 * 在 application.yml 中声明（便于按环境调参）。这里只覆写一件事：当有界队列
 * 打满、且线程已扩到 max 时，<b>不抛 RejectedExecutionException</b>，而是让
 * 提交任务的线程（createMemory 的 web 线程）自己同步跑这次增强。
 *
 * <p>效果：高并发写记忆时形成天然背压——队列满了，新的写请求自己承担一次
 * 增强耗时，从而自动放慢上游接收速率（流量削峰），而不是无界堆积撑爆内存或
 * 直接拒绝丢任务。这比无界队列（默认）和 AbortPolicy（默认拒绝即异常）都更稳。
 */
@Configuration
public class AsyncExecutorConfig {

    @Bean
    public TaskExecutorCustomizer callerRunsBackpressureCustomizer() {
        return (ThreadPoolTaskExecutor executor) ->
                executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
