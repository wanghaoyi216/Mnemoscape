package com.mnemoscape.memory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mnemoscape.memory.config.MemoryMinioProperties;
import com.mnemoscape.memory.model.entity.ChatMessage;
import com.mnemoscape.memory.repository.ChatMemoryRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 聊天记忆冷热分层存储服务 (MemoryTieringService)。
 *
 * <p>三层存储架构：
 * <ol>
 *   <li><b>Hot Tier (热层)</b>：Redis 缓存 {@code chat:memory:{userId}:{sessionId}}，TTL 7天，保障毫秒级低延迟读取。</li>
 *   <li><b>Warm Tier (温层)</b>：MySQL {@code chat_messages} 表（未归档记录），存储 30 天内的活跃历史消息。</li>
 *   <li><b>Cold Tier (冷层)</b>：MinIO 对象存储 {@code memory-archive} 桶，路径 {@code {userId}/{yyyyMM}/messages_{timestamp}.json}，
 *       存储 >30 天的已归档批量 JSON 数据。读取冷数据后异步写回 Redis 热层缓存。</li>
 * </ol>
 */
@Service
public class MemoryTieringService {

    private static final Logger log = LoggerFactory.getLogger(MemoryTieringService.class);

    private static final String REDIS_KEY_PREFIX = "chat:memory:";
    private static final Duration HOT_TIER_TTL = Duration.ofDays(7);
    private static final DateTimeFormatter YYYY_MM_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final ChatMemoryRepository chatMemoryRepository;
    private final MinioClient minioClient;
    private final MemoryMinioProperties minioProperties;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    public MemoryTieringService(ChatMemoryRepository chatMemoryRepository,
                                MinioClient minioClient,
                                MemoryMinioProperties minioProperties,
                                ObjectProvider<StringRedisTemplate> redisProvider,
                                ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.redisProvider = redisProvider;

        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        if (mapper != null) {
            this.objectMapper = mapper.copy()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        } else {
            this.objectMapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }

    /**
     * 保存新消息（写入温层 MySQL 并刷新/追加至热层 Redis）。
     */
    @Transactional
    public ChatMessage saveMessage(ChatMessage message) {
        if (message == null) return null;
        ChatMessage saved = chatMemoryRepository.save(message);
        writeToHotCache(saved);
        return saved;
    }

    /**
     * 多层检索链路：Hot (Redis) -> Warm (MySQL) -> Cold (MinIO) -> 异步写回 Redis。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 聊天记忆消息列表（按时间升序）
     */
    public List<ChatMessage> getMessages(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        // 1. Hot Tier (Redis) 探测
        List<ChatMessage> hotMessages = readFromHotCache(userId, sessionId);
        if (hotMessages != null && !hotMessages.isEmpty()) {
            log.debug("[MemoryTiering] Hot tier HIT for user={} session={}, count={}", userId, sessionId, hotMessages.size());
            return hotMessages;
        }

        // 2. Warm Tier (MySQL) 探测
        List<ChatMessage> warmMessages = chatMemoryRepository
                .findByUserIdAndSessionIdAndArchivedFalseOrderByCreatedAtAsc(userId, sessionId);
        if (warmMessages != null && !warmMessages.isEmpty()) {
            log.debug("[MemoryTiering] Warm tier HIT for user={} session={}, count={}", userId, sessionId, warmMessages.size());
            populateHotCache(userId, sessionId, warmMessages);
            return warmMessages;
        }

        // 3. Cold Tier (MinIO) 回退与归档检索
        log.info("[MemoryTiering] Hot & Warm miss, falling back to Cold Tier (MinIO) for user={} session={}", userId, sessionId);
        List<ChatMessage> coldMessages = readFromColdArchive(userId, sessionId);
        if (coldMessages != null && !coldMessages.isEmpty()) {
            log.info("[MemoryTiering] Cold tier HIT for user={} session={}, count={}. Async back-populating Redis.",
                    userId, sessionId, coldMessages.size());
            CompletableFuture.runAsync(() -> populateHotCache(userId, sessionId, coldMessages));
            return coldMessages;
        }

        return List.of();
    }

    /**
     * 定时冷热分层归档作业：
     * 每天凌晨扫描 >30 天未归档的消息，按 userId 和 yyyyMM 分组序列化 JSON 上传 MinIO，并更新 MySQL archived 状态。
     */
    @Scheduled(cron = "${mnemoscape.tiering.cron:0 0 2 * * ?}")
    @Transactional
    public int runTieringJob() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        return archiveColdMessages(cutoff);
    }

    /**
     * 执行冷数据归档核心逻辑。
     *
     * @param cutoff 截止时间（此前创建的消息视为冷数据）
     * @return 成功归档的消息条数
     */
    @Transactional
    public int archiveColdMessages(LocalDateTime cutoff) {
        log.info("[MemoryTiering] Starting ChatMemory tiering archive job, cutoff={}", cutoff);
        List<ChatMessage> oldMessages = chatMemoryRepository.findByArchivedFalseAndCreatedAtBefore(cutoff);
        if (oldMessages == null || oldMessages.isEmpty()) {
            log.info("[MemoryTiering] No unarchived messages found before {}", cutoff);
            return 0;
        }

        String bucket = minioProperties.getArchiveBucket();
        ensureBucketExists(bucket);

        // 按 userId + yyyyMM 分组
        Map<String, List<ChatMessage>> groupedByUserAndMonth = oldMessages.stream()
                .collect(Collectors.groupingBy(m -> {
                    String user = m.getUserId();
                    LocalDateTime dt = m.getCreatedAt() != null ? m.getCreatedAt() : LocalDateTime.now();
                    String yyyyMM = dt.format(YYYY_MM_FMT);
                    return user + "/" + yyyyMM;
                }));

        int totalArchived = 0;
        for (Map.Entry<String, List<ChatMessage>> entry : groupedByUserAndMonth.entrySet()) {
            String groupKey = entry.getKey(); // "userId/yyyyMM"
            List<ChatMessage> messages = entry.getValue();
            if (messages.isEmpty()) continue;

            String objectKey = String.format("%s/messages_%d.json", groupKey, System.currentTimeMillis());
            try {
                byte[] jsonBytes = objectMapper.writeValueAsBytes(messages);
                try (ByteArrayInputStream bais = new ByteArrayInputStream(jsonBytes)) {
                    minioClient.putObject(
                            PutObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(objectKey)
                                    .stream(bais, jsonBytes.length, -1)
                                    .contentType("application/json")
                                    .build()
                    );
                }

                List<String> ids = messages.stream().map(ChatMessage::getId).collect(Collectors.toList());
                chatMemoryRepository.markArchived(ids, objectKey);
                totalArchived += messages.size();
                log.info("[MemoryTiering] Successfully archived {} messages to MinIO object: {}/{}", messages.size(), bucket, objectKey);
            } catch (Exception e) {
                log.error("[MemoryTiering] Failed to archive messages for groupKey {}: {}", groupKey, e.getMessage(), e);
            }
        }

        log.info("[MemoryTiering] ChatMemory tiering job completed. Total messages archived: {}", totalArchived);
        return totalArchived;
    }

    // =========================================================================
    // Hot Tier (Redis) 操作
    // =========================================================================

    private String getRedisKey(String userId, String sessionId) {
        return REDIS_KEY_PREFIX + userId + ":" + sessionId;
    }

    private List<ChatMessage> readFromHotCache(String userId, String sessionId) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) return null;

            String key = getRedisKey(userId, sessionId);
            String jsonStr = redis.opsForValue().get(key);
            if (jsonStr == null || jsonStr.isBlank()) {
                return null;
            }
            return objectMapper.readValue(jsonStr, new TypeReference<List<ChatMessage>>() {});
        } catch (Exception e) {
            log.warn("[MemoryTiering] Hot tier read failed (fail-open): {}", e.getMessage());
            return null;
        }
    }

    private void populateHotCache(String userId, String sessionId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) return;

            String key = getRedisKey(userId, sessionId);
            String jsonStr = objectMapper.writeValueAsString(messages);
            redis.opsForValue().set(key, jsonStr, HOT_TIER_TTL);
        } catch (Exception e) {
            log.warn("[MemoryTiering] Hot tier write failed (fail-open): {}", e.getMessage());
        }
    }

    private void writeToHotCache(ChatMessage message) {
        if (message == null || message.getUserId() == null || message.getSessionId() == null) return;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) return;

            String key = getRedisKey(message.getUserId(), message.getSessionId());
            String jsonStr = redis.opsForValue().get(key);
            List<ChatMessage> list;
            if (jsonStr != null && !jsonStr.isBlank()) {
                list = objectMapper.readValue(jsonStr, new TypeReference<List<ChatMessage>>() {});
            } else {
                list = new ArrayList<>();
            }
            list.add(message);
            redis.opsForValue().set(key, objectMapper.writeValueAsString(list), HOT_TIER_TTL);
        } catch (Exception e) {
            log.warn("[MemoryTiering] Hot tier append failed (fail-open): {}", e.getMessage());
        }
    }

    // =========================================================================
    // Cold Tier (MinIO) 操作
    // =========================================================================

    public List<ChatMessage> readFromColdArchive(String userId, String sessionId) {
        String bucket = minioProperties.getArchiveBucket();
        List<ChatMessage> matchedMessages = new ArrayList<>();

        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                return matchedMessages;
            }

            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(userId + "/")
                            .recursive(true)
                            .build()
            );

            for (Result<Item> result : results) {
                Item item = result.get();
                String objectName = item.objectName();
                if (objectName == null || !objectName.endsWith(".json")) {
                    continue;
                }

                try (InputStream stream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectName)
                                .build())) {
                    byte[] data = stream.readAllBytes();
                    List<ChatMessage> archivedList = objectMapper.readValue(data, new TypeReference<List<ChatMessage>>() {});
                    if (archivedList != null) {
                        for (ChatMessage msg : archivedList) {
                            if (sessionId == null || sessionId.equals(msg.getSessionId())) {
                                matchedMessages.add(msg);
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[MemoryTiering] Failed to read cold archive object {}: {}", objectName, ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[MemoryTiering] Cold tier archive lookup failed for user={} bucket={}: {}", userId, bucket, e.getMessage());
        }

        matchedMessages.sort(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return matchedMessages;
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MemoryTiering] Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("[MemoryTiering] Could not ensure MinIO bucket {}: {}", bucket, e.getMessage());
        }
    }
}
