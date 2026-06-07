package com.mnemoscape.auth.admin.config;

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
 * Cache configuration for the auth-service-side admin aggregation endpoint
 * {@code GET /api/v1/admin/stats/active-users} (admin-dashboard task 6.6,
 * Requirements 14.1 / 14.2 / 14.3).
 *
 * <p>Wires a single Redis-backed cache:
 * <ul>
 *   <li>{@code admin.active-users} — TTL 60s (KPI overview tier per design
 *       §Caching Strategy)</li>
 * </ul>
 *
 * <p>The {@link RedisCacheManager} is exposed as a named bean and then
 * decorated by {@link BypassOnFailureCacheManager} which is published as
 * the {@link Primary @Primary} {@link CacheManager} so any Redis outage
 * silently degrades to "no cache" rather than surfacing a 5xx (R14.3).
 *
 * <p>{@code disableCreateOnMissingCache()} ensures unknown cache names are
 * surfaced as configuration errors at first call rather than silently
 * materialised with the default TTL.
 */
@Configuration
@EnableCaching
public class AdminCacheConfig {

    /** Cache name for the active-users aggregation endpoint (R6, R14.2). */
    public static final String CACHE_ACTIVE_USERS = "admin.active-users";

    /**
     * Redis cache manager: scoped to the single admin cache used by
     * auth-service. Other services (memory-service / resonance-service)
     * have their own per-service {@code AdminCacheConfig} for their
     * respective cache names.
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

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofSeconds(60)))
                .withInitialCacheConfigurations(perCache)
                .disableCreateOnMissingCache()
                .build();
    }

    /**
     * Primary {@link CacheManager}: the Redis manager wrapped in the bypass
     * decorator so any Redis outage degrades to "no cache" rather than
     * propagating a 5xx (Requirements 14.3).
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisCacheManager adminRedisCacheManager) {
        return new BypassOnFailureCacheManager(adminRedisCacheManager);
    }

    /**
     * Object mapper used by the Redis value serializer. Configured with
     * {@code java.time} support, sorted map entries (byte-stable output for
     * upstream hashing), and a narrowly-scoped polymorphic type whitelist
     * limited to {@code com.mnemoscape.*} / {@code java.util.*} /
     * {@code java.time.*} / {@code java.lang.*}.
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
