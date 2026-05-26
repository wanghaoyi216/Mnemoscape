package com.mnemoscape.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 控制 NVIDIA / 上游 LLM 接入的配置：
 *  - {@link #placeholderKeyPrefix} 命中 → 跳过真实调用，直接返回结构化降级；
 *  - {@link #timeoutMs}                上游单次请求硬上限；
 *  - {@link #fallbackMessage}          错误回放给前端的友好文案。
 *
 * 通过 {@code @ConfigurationProperties("mnemoscape.ai.upstream")} 绑定。
 */
@Configuration
@ConfigurationProperties(prefix = "mnemoscape.ai.upstream")
public class AiUpstreamProperties {

    private String placeholderKeyPrefix = "nvapi-placeholder";
    private long timeoutMs = 30_000;
    private String fallbackMessage =
            "AI 暂时不可用。请确认服务端已注入 NVIDIA_API_KEY 环境变量后重试。";

    public String getPlaceholderKeyPrefix() { return placeholderKeyPrefix; }
    public void setPlaceholderKeyPrefix(String placeholderKeyPrefix) {
        this.placeholderKeyPrefix = placeholderKeyPrefix;
    }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getFallbackMessage() { return fallbackMessage; }
    public void setFallbackMessage(String fallbackMessage) {
        this.fallbackMessage = fallbackMessage;
    }
}
