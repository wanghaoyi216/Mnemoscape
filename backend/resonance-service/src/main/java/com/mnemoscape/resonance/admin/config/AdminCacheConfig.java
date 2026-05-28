package com.mnemoscape.resonance.admin.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mnemoscape.common.admin.cache.BypassOnFailureCacheManager;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
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
 * Cache configuration for the resonance-service-side admin aggregation
 * endpoints (admin-dashboard task 8.2, Requirements 14.1 / 14.2 / 14.3).
 *
 * <p>Two Redis caches:
 * <ul>
 *   <li>{@code admin.resonance-overview} — TTL 120s</li>
 *   <li>{@code admin.resonance-top}      — TTL 120s</li>
 * </ul>
 *
 * <p>The {@link RedisCacheManager} is exposed as a named bean and decorated
 * by {@link BypassOnFailureCacheManager}, published as {@link Primary} so
 * any Redis outage degrades to "no cache" rather than surfacing a 5xx
 * (R14.3).
 *
 * <p>{@code disableCreateOnMissingCache()} ensures that any unconfigured
 * cache name fails fast instead of being silently materialized with the
 * default TTL — useful when refactoring cache names later.
 */
@Configuration
@EnableCaching
public class AdminCacheConfig {

    /** Cache name for the resonance-overview aggregation endpoint (R12.1). */
    public static final String CACHE_RESONANCE_OVERVIEW = "admin.resonance-overview";

    /** Cache name for the resonance-top edges endpoint (R12.2). */
    public static final String CACHE_RESONANCE_TOP = "admin.resonance-top";

    @Bean
    public RedisCacheManager adminRedisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer(adminCacheObjectMapper())))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(CACHE_RESONANCE_OVERVIEW, base.entryTtl(Duration.ofSeconds(120)));
        perCache.put(CACHE_RESONANCE_TOP, base.entryTtl(Duration.ofSeconds(120)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofSeconds(120)))
                .withInitialCacheConfigurations(perCache)
                .disableCreateOnMissingCache()
                .build();
    }

    /**
     * Primary cache manager: bypass-on-failure decorator wrapping the Redis
     * manager. Any Redis outage transparently degrades to "no cache".
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisCacheManager adminRedisCacheManager) {
        return new BypassOnFailureCacheManager(adminRedisCacheManager);
    }

    /**
     * Object mapper for the Redis value serializer. Configured for stable
     * byte-level output (sorted map entries, ISO-8601 dates) and a narrow
     * polymorphic-type whitelist scoped to project / standard packages.
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
