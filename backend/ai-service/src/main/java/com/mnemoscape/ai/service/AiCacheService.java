package com.mnemoscape.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.ai.config.AiCacheProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.ai.tools.MilvusSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * AI service 的 Redis 性能加速层 — 集中管控三类与 NVIDIA 上游相关的开销：
 *
 * <ol>
 *   <li><b>Embedding 缓存</b>：{@code embed(text)} 给定 (model, inputType, text)
 *       哈希后查 Redis，命中则跳过 NVIDIA 调用。默认 7 天 TTL，省 Token 配额，
 *       且把 ~200ms 的 HTTPS 调用降到亚毫秒级 Redis 查询。</li>
 *   <li><b>Milvus 搜索结果缓存</b>：给定 (userId, topK, query)，60 秒内复用上次召回。
 *       写入失效由 memory-service 在记忆创建/更新/删除时通过 RabbitMQ 触发
 *       {@link #invalidateUserSearch} 主动清除（A 系列改造）。</li>
 *   <li><b>NVIDIA 上游限流</b>：滑动窗口令牌桶，按"模型桶 + epochMinute"做 Key，
 *       INCR 超过 RPM 阈值则抛 {@link AiUpstreamException}（{@code RATE_LIMITED}），
 *       让上游 429 永远打不到 NVIDIA。</li>
 * </ol>
 *
 * <p><b>失败哲学</b>：所有方法对 Redis 异常都是 fail-open —— 读失败返回"缓存未命中"，
 * 写失败静默丢弃，限流失败放行。Redis 抖动绝不阻塞主业务调用 NVIDIA。
 *
 * <p><b>为何用 {@link ObjectProvider}{@code <StringRedisTemplate>}</b>：单测环境
 * 没有 Redis 连接也能起 ai-service（{@code spring.autoconfigure.exclude} 把
 * RedisAutoConfiguration 关掉的场景），bean 缺失时 provider 返回 null，三类
 * 功能直接走"未开启"路径。
 */
@Service
public class AiCacheService {

    private static final Logger log = LoggerFactory.getLogger(AiCacheService.class);

    /** Embedding 缓存 key 前缀：{@code ai:embed:{model}:{type}:{sha256}} */
    private static final String EMBED_PREFIX = "ai:embed:";
    /** 搜索结果缓存 key 前缀：{@code ai:search:{userId}:{topK}:{sha256}} */
    private static final String SEARCH_PREFIX = "ai:search:";
    /** 搜索结果缓存"按用户"反向索引集合：{@code ai:search:idx:{userId}}，元素是该用户名下所有 SEARCH_PREFIX key */
    private static final String SEARCH_INDEX_PREFIX = "ai:search:idx:";
    /** 限流计数 key 前缀：{@code ai:rl:{bucket}:{epochMinute}} */
    private static final String RATELIMIT_PREFIX = "ai:rl:";

    /** Embedding 限流桶名（与具体 model 解耦，因为 embedding 走的是统一 endpoint）。 */
    public static final String BUCKET_EMBED = "embed";
    /** 视觉模型限流桶名。 */
    public static final String BUCKET_VISION = "vision";
    /** Chat / 推理 / Agentic 等文本生成模型共享的桶名（默认 RPM）。 */
    public static final String BUCKET_CHAT = "chat";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final AiCacheProperties props;
    private final ObjectMapper json = new ObjectMapper();

    public AiCacheService(ObjectProvider<StringRedisTemplate> redisProvider,
                          AiCacheProperties props) {
        this.redisProvider = redisProvider;
        this.props = props;
    }

    // ============================================================ Embedding 缓存

    /**
     * 查 embedding 缓存。
     *
     * @return 命中时返回向量；未命中 / Redis 不可用 / 缓存关掉时返回 null
     */
    public float[] getCachedEmbedding(String model, String inputType, String text) {
        if (!props.getCache().getEmbedding().isEnabled()) return null;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return null;
        try {
            String key = embedKey(model, inputType, text);
            String b64 = redis.opsForValue().get(key);
            if (b64 == null) return null;
            return decodeFloatArray(b64);
        } catch (Exception e) {
            log.debug("[AiCache] embedding read failed (silent): {}", e.toString());
            return null;
        }
    }

    /** 写入 embedding 缓存。失败静默。 */
    public void cacheEmbedding(String model, String inputType, String text, float[] vector) {
        if (!props.getCache().getEmbedding().isEnabled()) return;
        if (vector == null || vector.length == 0) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            String key = embedKey(model, inputType, text);
            String b64 = encodeFloatArray(vector);
            redis.opsForValue().set(key, b64, Duration.ofSeconds(props.getCache().getEmbedding().getTtlSeconds()));
        } catch (Exception e) {
            log.debug("[AiCache] embedding write failed (silent): {}", e.toString());
        }
    }

    // ============================================================ Milvus 搜索缓存

    /** 命中时返回 Hit 列表；未命中 / 关掉 / Redis 不可用 / 反序列化失败返回 null。 */
    public List<MilvusSearchTool.Hit> getCachedSearch(String userId, int topK, String query) {
        if (!props.getCache().getSearch().isEnabled()) return null;
        if (userId == null || query == null || query.isBlank()) return null;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return null;
        try {
            String key = searchKey(userId, topK, query);
            String body = redis.opsForValue().get(key);
            if (body == null) return null;
            return json.readValue(body, new TypeReference<List<MilvusSearchTool.Hit>>() {});
        } catch (Exception e) {
            log.debug("[AiCache] search read failed (silent): {}", e.toString());
            return null;
        }
    }

    /** 写入搜索结果缓存。同时把 key 加入 SEARCH_INDEX_PREFIX:{userId} 集合用于主动失效。 */
    public void cacheSearch(String userId, int topK, String query, List<MilvusSearchTool.Hit> hits) {
        if (!props.getCache().getSearch().isEnabled()) return;
        if (userId == null || query == null || query.isBlank() || hits == null) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            String key = searchKey(userId, topK, query);
            String body = json.writeValueAsString(hits);
            Duration ttl = Duration.ofSeconds(props.getCache().getSearch().getTtlSeconds());
            redis.opsForValue().set(key, body, ttl);
            // 反向索引：让 invalidateUserSearch(userId) 能找到该用户所有 search key
            String idxKey = SEARCH_INDEX_PREFIX + userId;
            redis.opsForSet().add(idxKey, key);
            // 索引 TTL 比单条 cache 略长，避免索引先于内容过期导致清不掉
            redis.expire(idxKey, ttl.plusMinutes(5));
        } catch (Exception e) {
            log.debug("[AiCache] search write failed (silent): {}", e.toString());
        }
    }

    /**
     * 主动失效某用户的所有搜索缓存。
     *
     * <p>调用时机（A 系列 RabbitMQ Consumer 触发）：
     * <ul>
     *   <li>该用户新建/更新/删除记忆 → memory.indexed 事件</li>
     *   <li>该用户的记忆被向量回填 → admin/backfill-vectors 完成事件</li>
     * </ul>
     */
    public int invalidateUserSearch(String userId) {
        if (userId == null) return 0;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return 0;
        try {
            String idxKey = SEARCH_INDEX_PREFIX + userId;
            var members = redis.opsForSet().members(idxKey);
            if (members == null || members.isEmpty()) return 0;
            Long removed = redis.delete(members);
            redis.delete(idxKey);
            log.info("[AiCache] invalidated {} search cache entries for userId={}", removed, userId);
            return removed == null ? 0 : removed.intValue();
        } catch (Exception e) {
            log.warn("[AiCache] invalidate failed for userId={}: {}", userId, e.toString());
            return 0;
        }
    }

    // ============================================================ NVIDIA 限流

    /**
     * 令牌桶（按分钟滑动窗口）尝试获取一次配额。
     *
     * @param bucket  逻辑桶名（见常量 {@link #BUCKET_EMBED}/{@link #BUCKET_VISION}/{@link #BUCKET_CHAT}）
     * @throws AiUpstreamException 桶不存在 → 用 defaultRpm；超额抛 RATE_LIMITED
     */
    public void acquireOrThrow(String bucket) {
        if (!props.getRateLimit().isEnabled()) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return; // Redis 不可用 → 直接放行
        int limit = limitFor(bucket);
        long epochMin = System.currentTimeMillis() / 60_000L;
        String key = RATELIMIT_PREFIX + bucket + ":" + epochMin;
        try {
            Long current = redis.opsForValue().increment(key);
            if (current != null && current == 1L) {
                // 第一个请求设过期；70s 让窗口稍微重叠，避免边界毛刺
                redis.expire(key, Duration.ofSeconds(70));
            }
            if (current != null && current > limit) {
                log.warn("[AiCache] rate-limited: bucket={} usage={}/{} window=min:{}",
                        bucket, current, limit, epochMin);
                throw new AiUpstreamException(AiUpstreamException.Reason.RATE_LIMITED,
                        "本地限流: " + bucket + " 桶 " + current + "/" + limit + " RPM, 请稍后重试");
            }
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            // Redis 抖动 → 静默放行
            log.debug("[AiCache] rate-limit check failed (silent allow): {}", e.toString());
        }
    }

    private int limitFor(String bucket) {
        if (BUCKET_EMBED.equals(bucket)) return props.getRateLimit().getEmbedRpm();
        if (BUCKET_VISION.equals(bucket)) return props.getRateLimit().getVisionRpm();
        return props.getRateLimit().getDefaultRpm();
    }

    // ============================================================ key 构造 / 序列化

    private static String embedKey(String model, String inputType, String text) {
        return EMBED_PREFIX + safe(model) + ":" + safe(inputType) + ":" + sha256(text);
    }

    private static String searchKey(String userId, int topK, String query) {
        return SEARCH_PREFIX + safe(userId) + ":" + topK + ":" + sha256(query);
    }

    private static String safe(String s) {
        if (s == null) return "_";
        return s.replace(':', '_').replace(' ', '_');
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bs = md.digest(Objects.requireNonNullElse(text, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bs.length * 2);
            for (byte b : bs) sb.append(String.format("%02x", b));
            // 取前 24 字符就够避撞，缩短 key 长度便于 Redis 内存效率
            return sb.substring(0, 24);
        } catch (Exception e) {
            // SHA-256 在 JVM 必有；这里仅为编译期不抛
            return Integer.toHexString(Objects.hashCode(text));
        }
    }

    /** float[] → ByteBuffer (大端) → base64 字符串。一次性 4*N + Base64 开销，性能远优于 JSON。 */
    private static String encodeFloatArray(float[] arr) {
        ByteBuffer bb = ByteBuffer.allocate(arr.length * 4);
        for (float f : arr) bb.putFloat(f);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    private static float[] decodeFloatArray(String b64) {
        byte[] raw = Base64.getDecoder().decode(b64);
        if (raw.length % 4 != 0) throw new IllegalStateException("Corrupt embedding cache entry");
        ByteBuffer bb = ByteBuffer.wrap(raw);
        float[] out = new float[raw.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = bb.getFloat();
        return out;
    }
}
