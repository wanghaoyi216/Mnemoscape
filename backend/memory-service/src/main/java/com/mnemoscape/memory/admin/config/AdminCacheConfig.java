package com.mnemoscape.memory.admin.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.mnemoscape.common.admin.cache.BypassOnFailureCacheManager;

import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.CompositeCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache configuration for the admin-dashboard aggregation endpoints owned
 * by memory-service (admin-dashboard tasks 7.8 / 7.9 / 7.11 / 7.13 / 7.17 /
 * 7.19, Requirements 14.1, 14.2, 14.3).
 *
 * <p>Two physically distinct cache backends coexist:
 * <ul>
 *   <li>A {@link RedisCacheManager} configured with per-cache TTLs for the
 *       six admin aggregation caches (admin.active-users, admin.memory-trends,
 *       admin.emotion-distribution, admin.heatmap, admin.top-contributors,
 *       admin.fragment-discovery). TTLs follow design §Caching Strategy.
 *       {@link RedisCacheManager.RedisCacheManagerBuilder#disableCreateOnMissingCache()
 *       disableCreateOnMissingCache} ensures unknown names return {@code null}
 *       instead of being implicitly materialized with the default config —
 *       that way the legacy Caffeine cache names (memories / driftStates)
 *       are not accidentally hijacked into Redis.</li>
 *   <li>A {@link CaffeineCacheManager} that preserves the pre-existing
 *       in-process caches used by {@code MemoryService} / {@code MemoryLookup}
 *       ({@code memories} and {@code driftStates}). Spring Boot's
 *       {@code CacheAutoConfiguration} would normally synthesize this for us
 *       based on {@code spring.cache.type=caffeine}, but declaring our own
 *       {@code CacheManager} bean below disables that auto-configuration via
 *       {@code @ConditionalOnMissingBean(CacheManager.class)}, so we wire the
 *       Caffeine manager explicitly here with the same spec.</li>
 * </ul>
 *
 * <p>The two managers are composed via {@link CompositeCacheManager} (Redis
 * first, then Caffeine) and then wrapped in a {@link BypassOnFailureCacheManager}
 * exposed as the {@link Primary @Primary} {@code CacheManager} bean. Spring's
 * caching machinery will resolve admin cache names through the Redis manager
 * — wrapped in {@code BypassCache} so any Redis outage transparently degrades
 * to "no cache" without surfacing a 5xx (Requirements 14.3) — and falls back
 * to Caffeine for the legacy names. Since {@code BypassCache} only catches
 * Redis-specific exception types, wrapping the Caffeine cache is harmless.
 */
@Configuration
public class AdminCacheConfig {

    /** Cache name for the active-users aggregation endpoint (R6, R14.2). */
    public static final String CACHE_ACTIVE_USERS = "admin.active-users";

    /** Cache name for the memory-trends aggregation endpoint (R7, R14.2). */
    public static final String CACHE_MEMORY_TRENDS = "admin.memory-trends";

    /** Cache name for the emotion-distribution aggregation endpoint (R8, R14.2). */
    public static final String CACHE_EMOTION_DISTRIBUTION = "admin.emotion-distribution";

    /** Cache name for the heatmap aggregation endpoint (R9, R14.2). */
    public static final String CACHE_HEATMAP = "admin.heatmap";

    /** Cache name for the top-contributors aggregation endpoint (R10, R14.2). */
    public static final String CACHE_TOP_CONTRIBUTORS = "admin.top-contributors";

    /** Cache name for the fragment-discovery aggregation endpoint (R11, R14.2). */
    public static final String CACHE_FRAGMENT_DISCOVERY = "admin.fragment-discovery";

    /** Default Caffeine spec mirroring the previous {@code application.yml} value. */
    private static final String LEGACY_CAFFEINE_SPEC = "maximumSize=2000,expireAfterWrite=10m";

    /** Legacy cache name used by {@code MemoryLookup#findById}. */
    private static final String LEGACY_CACHE_MEMORIES = "memories";

    /** Legacy cache name used by drift-state caching. */
    private static final String LEGACY_CACHE_DRIFT_STATES = "driftStates";

    /**
     * Redis cache manager configured with per-cache TTLs for the six admin
     * aggregation caches. Unknown cache names return {@code null} so that
     * legacy callers fall through to the Caffeine manager via the composite.
     *
     * @param connectionFactory the Lettuce-backed connection factory supplied
     *                          by {@code spring-boot-starter-data-redis}
     * @return a Redis-backed cache manager scoped to the six admin caches
     */
    @Bean
    public RedisCacheManager adminRedisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer(adminCacheObjectMapper())))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(CACHE_ACTIVE_USERS, base.entryTtl(Duration.ofSeconds(60)));
        perCache.put(CACHE_MEMORY_TRENDS, base.entryTtl(Duration.ofSeconds(60)));
        perCache.put(CACHE_EMOTION_DISTRIBUTION, base.entryTtl(Duration.ofSeconds(120)));
        perCache.put(CACHE_HEATMAP, base.entryTtl(Duration.ofSeconds(900)));
        perCache.put(CACHE_TOP_CONTRIBUTORS, base.entryTtl(Duration.ofSeconds(120)));
        perCache.put(CACHE_FRAGMENT_DISCOVERY, base.entryTtl(Duration.ofSeconds(120)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofSeconds(60)))
                .withInitialCacheConfigurations(perCache)
                .disableCreateOnMissingCache()
                .build();
    }

    /**
     * Replacement for the auto-configured Caffeine cache manager that keeps
     * the pre-admin {@code memories} / {@code driftStates} caches working.
     * Mirrors the Caffeine spec previously set in {@code application.yml}.
     */
    @Bean
    public CaffeineCacheManager legacyCaffeineCacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(
                LEGACY_CACHE_MEMORIES, LEGACY_CACHE_DRIFT_STATES);
        mgr.setCaffeine(Caffeine.from(CaffeineSpec.parse(LEGACY_CAFFEINE_SPEC)));
        // Keep a closed cache-name set so admin cache names cannot accidentally
        // be served from the in-process Caffeine manager.
        mgr.setAllowNullValues(false);
        return mgr;
    }

    /**
     * Primary {@link CacheManager} bean: a Redis-first / Caffeine-fallback
     * composite wrapped in {@link BypassOnFailureCacheManager} so any Redis
     * outage on admin caches degrades to "no cache" rather than surfacing a
     * 5xx to admin endpoints (Requirements 14.3).
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisCacheManager adminRedisCacheManager,
                                     CaffeineCacheManager legacyCaffeineCacheManager) {
        CompositeCacheManager composite = new CompositeCacheManager(
                adminRedisCacheManager, legacyCaffeineCacheManager);
        // Fail fast on truly unknown cache names — better to surface a config
        // mistake at startup / first call than to silently no-op.
        composite.setFallbackToNoOpCache(false);
        return new BypassOnFailureCacheManager(composite);
    }

    /**
     * Object mapper used by the Redis value serializer for admin caches.
     *
     * <p>Configured to:
     * <ul>
     *   <li>round-trip {@code java.time} types (LocalDate / OffsetDateTime) as
     *       ISO-8601 strings rather than numeric timestamps so that cached
     *       payloads remain human-readable in Redis;</li>
     *   <li>activate default polymorphic typing restricted to
     *       {@code com.mnemoscape.*} packages so {@code GenericJackson2JsonRedisSerializer}
     *       can deserialize {@code List<Record>} payloads without ambiguity
     *       while keeping the gadget-deserialization surface small;</li>
     *   <li>order map entries by key — the {@link Map}-keyed cache values
     *       (e.g. {@code statusBreakdown}) become byte-stable in Redis so
     *       SHA-256 fingerprinting in upstream systems remains deterministic.</li>
     * </ul>
     */
    private static ObjectMapper adminCacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .allowIfSubType("com.mnemoscape.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.time.")
                        .allowIfSubType("java.lang.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                As.PROPERTY);
        return mapper;
    }
}
