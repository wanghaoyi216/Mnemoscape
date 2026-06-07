# Mnemoscape 异步图谱投影与 AI 提示词 Redis 缓存优化设计书

> 任务编号：TASK-OPT-ASYNC-CACHE-20260530
> 创建日期：2026-05-30
> 适用服务：memory-service, ai-service
> 状态：Detailed Design

---

## 一、 异步多线程并行投影架构设计

在原有设计中，记忆的 3D 重建、Neo4j 实体投影与 Milvus 向量化是串行跑在一个统一的后台异步作业中的：

```
[persistBaseMemory] ──(1)──> [triggerAsyncEnrichment]
                                    │
                                    ▼ (Async Thread)
                       [runEnrichmentAsync]
                                    │
                                    ├─► 1. enrichWithReconstruction (LLM 10-30s)
                                    ├─► 2. extractAndProjectGraph (Neo4j)
                                    └─► 3. indexMemoryVector (Milvus)
```

由于步骤 1 中包含重构 LLM 上游耗时（10-30s），会导致 Neo4j 图谱大屏以及语义共鸣召回有极其严重的时延。由于三者在数据上仅依赖主线程已经保存的 `BaseMemory`（包含 id, userId, title, description, location, year, privacy），因此可以完全解耦、并行处理。

### 1.1 并行异步分发模型

优化后的分发模型采用 Spring 代理机制，直接在 TaskExecutor 线程池中并发开启 3 个任务：

```
[persistBaseMemory] ──(1)──> [triggerAsyncEnrichment] (主线程)
                                    │
           ┌────────────────────────┼────────────────────────┐
           ▼ (Async Thread 1)       ▼ (Async Thread 2)       ▼ (Async Thread 3)
   [runEnrichmentAsync]     [runGraphProjectionAsync]  [runVectorIndexingAsync]
           │                        │                        │
           ▼                        ▼                        ▼
 [enrichWithReconstruction] [extractAndProjectGraph]    [indexMemoryVector]
       (LLM 10-30s)             (Neo4j 瞬间投影)        (Milvus 瞬间索引)
```

### 1.2 自代理机制与并发方法定义

由于 `@Async` 原理是通过 AOP 生成 JDK 动态代理/CGLIB 代理类来拦截调用，因此同类中直接 self-call（即 `this.runGraphProjectionAsync(...)`）会导致拦截失效，方法依旧在主线程同步执行。

我们复用 `MemoryService` 中已有的 `asyncEnrichmentSelf` 自代理注入（标注了 `@Autowired @Lazy`），并通过它并行唤醒 3 个异步入口：

```java
    private void triggerAsyncEnrichment(String memoryId) {
        try {
            asyncEnrichmentSelf.runEnrichmentAsync(memoryId);
        } catch (Exception e) {
            log.warn("Failed to dispatch async reconstruction enrichment for {}: {}", memoryId, e.toString());
        }
        try {
            asyncEnrichmentSelf.runGraphProjectionAsync(memoryId);
        } catch (Exception e) {
            log.warn("Failed to dispatch async graph projection for {}: {}", memoryId, e.toString());
        }
        try {
            asyncEnrichmentSelf.runVectorIndexingAsync(memoryId);
        } catch (Exception e) {
            log.warn("Failed to dispatch async vector indexing for {}: {}", memoryId, e.toString());
        }
    }
```

每个方法对应各自的处理边界与异常日志：
- `runEnrichmentAsync`：仅处理 3D 重建落库。
- `runGraphProjectionAsync`：仅处理 Neo4j 实体投影。
- `runVectorIndexingAsync`：仅处理 Milvus 向量化投递。

---

## 二、 Mascot 推荐问题缓存设计

为了达到 Mascot Panel 打开时 `intentHints` 响应延迟小于 5ms，我们将在 `ai-service` 端引入 Redis 缓存系统，并利用 `BypassOnFailureCacheManager` 确保缓存层异常不向应用层蔓延。

### 2.1 依赖关系引入

修改 `ai-service/pom.xml`，注入 Redis 与 Redis 序列化支撑：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

### 2.2 缓存配置 AiCacheConfig

在 `com.mnemoscape.ai.config` 包下新建 `AiCacheConfig.java`，配置一个专为 `intent-hints` 服务的 Redis 缓存实例。缓存 TTL 设为 **10分钟（600秒）**，以取得“减轻 LLM 额度损耗”和“用户记忆更新及时感”的完美平衡。

```java
package com.mnemoscape.ai.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mnemoscape.common.admin.cache.BypassOnFailureCacheManager;
import org.springframework.cache.CacheManager;
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

@Configuration
public class AiCacheConfig {

    public static final String CACHE_INTENT_HINTS = "intent-hints";

    @Bean
    public RedisCacheManager aiRedisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer(aiCacheObjectMapper())))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(CACHE_INTENT_HINTS, base.entryTtl(Duration.ofSeconds(600)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofSeconds(600)))
                .withInitialCacheConfigurations(perCache)
                .disableCreateOnMissingCache()
                .build();
    }

    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheManager redisManager = aiRedisCacheManager(connectionFactory);
        return new BypassOnFailureCacheManager(redisManager);
    }

    private static ObjectMapper aiCacheObjectMapper() {
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
```

### 2.3 稳定 MD5 缓存 Key 生成算法

由于传入的 `List<MemoryDigest>` 序列化后可能由于顺序、字符集等带来非常长的 Redis Key 并且可能对 SpEL 造成负担。为此，在 `IntentHintService` 内实现一个高容错、哈希紧凑的 deterministic MD5 Cache Key 生成器：

```java
    public static String cacheKey(List<MemoryDigest> digests, boolean zh) {
        if (digests == null || digests.isEmpty()) {
            return "fallback|" + zh;
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (MemoryDigest d : digests) {
                if (d == null) continue;
                sb.append(d.title() == null ? "" : d.title()).append('|');
                sb.append(d.location() == null ? "" : d.location()).append('|');
                sb.append(d.year() == null ? "" : d.year()).append('|');
            }
            byte[] hash = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString() + "|" + zh;
        } catch (Exception e) {
            return "error|" + zh + "|" + digests.size();
        }
    }
```

将该 Key 生成方法配装进 `@Cacheable`：

```java
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = "intent-hints",
            key = "T(com.mnemoscape.ai.service.IntentHintService).cacheKey(#digests, #zh)",
            sync = true
    )
    public List<String> generate(List<MemoryDigest> digests, boolean zh) {
        ...
    }
```

`sync = true` 可开启本地 JVM 并发 DCL（双重检查锁）锁，确保在热缓存穿透时，对相同 digest 集合的并发请求只会有一条击穿给 LLM，其余线程全部阻塞等待 Redis 缓存更新后快速返回，保护 AI 额度不受剧烈并发的抖动冲击。
