package com.mnemoscape.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

/**
 * AI service 的 Redis 性能加速层配置。
 *
 * <p>对应 {@code mnemoscape.ai.cache.*} 与 {@code mnemoscape.ai.rate-limit.*}
 * 两个块。所有开关默认 true；任一关掉走"无缓存直连 + 无限流"的旧行为。
 * 实际生效逻辑全在 {@link com.mnemoscape.ai.service.AiCacheService}。
 *
 * <p><b>不放在 {@link AiUpstreamProperties}</b> 是因为这层关注的是"性能/成本治理"，
 * 与"上游可用性"是正交关注点；分开后便于按 profile 单独覆盖（比如压测环境只关
 * 限流不关缓存）。
 */
@Configuration
@RefreshScope
@ConfigurationProperties(prefix = "mnemoscape.ai")
public class AiCacheProperties {

    private final Cache cache = new Cache();
    private final RateLimit rateLimit = new RateLimit();
    private final Quota quota = new Quota();

    public Cache getCache() { return cache; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Quota getQuota() { return quota; }

    public static class Cache {
        private final Embedding embedding = new Embedding();
        private final Search search = new Search();

        public Embedding getEmbedding() { return embedding; }
        public Search getSearch() { return search; }

        public static class Embedding {
            private boolean enabled = true;
            private long ttlSeconds = 604800L; // 7d
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public long getTtlSeconds() { return ttlSeconds; }
            public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        }

        public static class Search {
            private boolean enabled = true;
            private long ttlSeconds = 60L;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public long getTtlSeconds() { return ttlSeconds; }
            public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int defaultRpm = 40;
        private int embedRpm = 100;
        private int visionRpm = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDefaultRpm() { return defaultRpm; }
        public void setDefaultRpm(int defaultRpm) { this.defaultRpm = Math.max(1, defaultRpm); }
        public int getEmbedRpm() { return embedRpm; }
        public void setEmbedRpm(int embedRpm) { this.embedRpm = Math.max(1, embedRpm); }
        public int getVisionRpm() { return visionRpm; }
        public void setVisionRpm(int visionRpm) { this.visionRpm = Math.max(1, visionRpm); }
    }

    /**
     * Per-user 每日 Token 配额（移植自墨问 per-user LLM Token 配额思路）。
     * 与 {@link RateLimit} 的"全局 RPM 防上游 429"互补：这个按
     * "单用户 / 自然日 / token 总量"做成本治理，超限拒绝本次对话并返回友好提示。
     */
    public static class Quota {
        private boolean enabled = true;
        private long dailyTokenLimit = 200_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getDailyTokenLimit() { return dailyTokenLimit; }
        public void setDailyTokenLimit(long dailyTokenLimit) { this.dailyTokenLimit = Math.max(1, dailyTokenLimit); }
    }
}
