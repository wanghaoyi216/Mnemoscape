package com.mnemoscape.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mnemoscape.memory.config.MemoryMinioProperties;
import com.mnemoscape.memory.model.entity.ChatMessage;
import com.mnemoscape.memory.repository.ChatMemoryRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryTieringServiceTest {

    private ChatMemoryRepository chatMemoryRepository;
    private MinioClient minioClient;
    private MemoryMinioProperties minioProperties;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectProvider<StringRedisTemplate> redisProvider;
    private ObjectProvider<ObjectMapper> objectMapperProvider;
    private ObjectMapper objectMapper;

    private MemoryTieringService tieringService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatMemoryRepository = mock(ChatMemoryRepository.class);
        minioClient = mock(MinioClient.class);
        minioProperties = new MemoryMinioProperties();
        minioProperties.setArchiveBucket("memory-archive");

        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        objectMapperProvider = mock(ObjectProvider.class);
        when(objectMapperProvider.getIfAvailable()).thenReturn(objectMapper);

        tieringService = new MemoryTieringService(
                chatMemoryRepository,
                minioClient,
                minioProperties,
                redisProvider,
                objectMapperProvider
        );
    }

    @Test
    @DisplayName("1) Hot tier HIT: 当 Redis 中存在数据时，直接从 Redis 反序列化返回，不访问 DB")
    void testHotTierHit() throws Exception {
        String userId = "user-123";
        String sessionId = "session-456";
        String redisKey = "chat:memory:" + userId + ":" + sessionId;

        ChatMessage msg1 = new ChatMessage("msg-1", userId, sessionId, "USER", "你好", "TEXT", false, null, LocalDateTime.now().minusMinutes(5));
        ChatMessage msg2 = new ChatMessage("msg-2", userId, sessionId, "ASSISTANT", "你好，我是 Echo Envoy", "TEXT", false, null, LocalDateTime.now().minusMinutes(4));
        List<ChatMessage> expectedList = List.of(msg1, msg2);
        String jsonPayload = objectMapper.writeValueAsString(expectedList);

        when(valueOperations.get(redisKey)).thenReturn(jsonPayload);

        List<ChatMessage> result = tieringService.getMessages(userId, sessionId);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("msg-1", result.get(0).getId());
        assertEquals("你好", result.get(0).getContent());
        assertEquals("ASSISTANT", result.get(1).getRole());

        // 验证没有查询 DB
        verify(chatMemoryRepository, never()).findByUserIdAndSessionIdAndArchivedFalseOrderByCreatedAtAsc(anyString(), anyString());
    }

    @Test
    @DisplayName("2) Warm tier HIT: Redis 未命中时从 MySQL 查出未归档消息，并写回 Redis 热层缓存")
    void testWarmTierHit() {
        String userId = "user-123";
        String sessionId = "session-456";
        String redisKey = "chat:memory:" + userId + ":" + sessionId;

        // Redis 缓存未命中
        when(valueOperations.get(redisKey)).thenReturn(null);

        ChatMessage msg = new ChatMessage("msg-warm-1", userId, sessionId, "USER", "温层消息内容", "TEXT", false, null, LocalDateTime.now().minusDays(2));
        when(chatMemoryRepository.findByUserIdAndSessionIdAndArchivedFalseOrderByCreatedAtAsc(userId, sessionId))
                .thenReturn(List.of(msg));

        List<ChatMessage> result = tieringService.getMessages(userId, sessionId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("msg-warm-1", result.get(0).getId());
        assertEquals("温层消息内容", result.get(0).getContent());

        // 验证写回 Redis 缓存
        verify(valueOperations).set(eq(redisKey), anyString(), any());
    }

    @Test
    @DisplayName("3) Cold tier HIT: Redis 与 MySQL 皆未命中时，从 MinIO 归档桶拉取 JSON 并解析返回")
    @SuppressWarnings("unchecked")
    void testColdTierHit() throws Exception {
        String userId = "user-123";
        String sessionId = "session-cold";
        String redisKey = "chat:memory:" + userId + ":" + sessionId;

        when(valueOperations.get(redisKey)).thenReturn(null);
        when(chatMemoryRepository.findByUserIdAndSessionIdAndArchivedFalseOrderByCreatedAtAsc(userId, sessionId))
                .thenReturn(Collections.emptyList());

        // Mock MinIO bucket exists
        when(minioClient.bucketExists(any())).thenReturn(true);

        // Mock Item
        Item mockItem = mock(Item.class);
        when(mockItem.objectName()).thenReturn(userId + "/202607/messages_1001.json");
        Result<Item> mockResult = mock(Result.class);
        when(mockResult.get()).thenReturn(mockItem);

        when(minioClient.listObjects(any())).thenReturn(List.of(mockResult));

        ChatMessage coldMsg = new ChatMessage("msg-cold-1", userId, sessionId, "USER", "冷归档消息", "TEXT", true, userId + "/202607/messages_1001.json", LocalDateTime.now().minusDays(40));
        byte[] jsonBytes = objectMapper.writeValueAsBytes(List.of(coldMsg));

        GetObjectResponse getObjectResponse = mock(GetObjectResponse.class);
        when(getObjectResponse.readAllBytes()).thenReturn(jsonBytes);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse);

        List<ChatMessage> result = tieringService.getMessages(userId, sessionId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("msg-cold-1", result.get(0).getId());
        assertEquals("冷归档消息", result.get(0).getContent());
        assertTrue(result.get(0).getArchived());
    }

    @Test
    @DisplayName("4) 归档作业: archiveColdMessages 正确按 userId/yyyyMM 分组上传 MinIO 并标记 DB 已归档")
    void testArchiveColdMessages() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 12, 0);
        LocalDateTime oldDate1 = LocalDateTime.of(2026, 6, 15, 10, 0);
        LocalDateTime oldDate2 = LocalDateTime.of(2026, 6, 20, 15, 30);
        LocalDateTime oldDate3 = LocalDateTime.of(2026, 7, 5, 9, 0);

        ChatMessage msg1 = new ChatMessage("id-1", "user-A", "s-1", "USER", "A的六月消息1", "TEXT", false, null, oldDate1);
        ChatMessage msg2 = new ChatMessage("id-2", "user-A", "s-1", "ASSISTANT", "A的六月消息2", "TEXT", false, null, oldDate2);
        ChatMessage msg3 = new ChatMessage("id-3", "user-A", "s-2", "USER", "A的七月消息", "TEXT", false, null, oldDate3);
        ChatMessage msg4 = new ChatMessage("id-4", "user-B", "s-3", "USER", "B的六月消息", "TEXT", false, null, oldDate1);

        List<ChatMessage> oldList = List.of(msg1, msg2, msg3, msg4);
        when(chatMemoryRepository.findByArchivedFalseAndCreatedAtBefore(any(LocalDateTime.class))).thenReturn(oldList);
        when(minioClient.bucketExists(any())).thenReturn(true);

        int count = tieringService.archiveColdMessages(now.minusDays(30));

        assertEquals(4, count);

        // 验证 PutObjectArgs 调用（3个组：user-A/202606, user-A/202607, user-B/202606）
        ArgumentCaptor<PutObjectArgs> putCaptor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, org.mockito.Mockito.atLeast(3)).putObject(putCaptor.capture());

        List<PutObjectArgs> capturedArgs = putCaptor.getAllValues();
        assertTrue(capturedArgs.stream().anyMatch(a -> a.object().startsWith("user-A/202606/messages_")));
        assertTrue(capturedArgs.stream().anyMatch(a -> a.object().startsWith("user-A/202607/messages_")));
        assertTrue(capturedArgs.stream().anyMatch(a -> a.object().startsWith("user-B/202606/messages_")));

        // 验证 markArchived 更新
        verify(chatMemoryRepository).markArchived(eq(List.of("id-1", "id-2")), anyString());
        verify(chatMemoryRepository).markArchived(eq(List.of("id-3")), anyString());
        verify(chatMemoryRepository).markArchived(eq(List.of("id-4")), anyString());
    }

    @Test
    @DisplayName("5) 保存消息: saveMessage 写入 DB 并刷新热层缓存")
    void testSaveMessage() {
        String userId = "user-save";
        String sessionId = "session-save";
        ChatMessage newMsg = new ChatMessage("new-1", userId, sessionId, "USER", "最新输入", "TEXT", false, null, LocalDateTime.now());

        when(chatMemoryRepository.save(newMsg)).thenReturn(newMsg);
        when(valueOperations.get("chat:memory:" + userId + ":" + sessionId)).thenReturn(null);

        ChatMessage saved = tieringService.saveMessage(newMsg);

        assertNotNull(saved);
        assertEquals("new-1", saved.getId());
        verify(chatMemoryRepository).save(newMsg);
        verify(valueOperations).set(eq("chat:memory:" + userId + ":" + sessionId), anyString(), any());
    }
}
