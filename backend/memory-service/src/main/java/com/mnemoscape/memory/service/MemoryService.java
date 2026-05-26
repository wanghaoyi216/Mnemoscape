package com.mnemoscape.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.memory.client.AiServiceClient;
import com.mnemoscape.memory.model.dto.CreateMemoryRequest;
import com.mnemoscape.memory.model.dto.EntityExtractionResponse;
import com.mnemoscape.memory.model.dto.UpdateMemoryRequest;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.model.entity.MemoryFragment;
import com.mnemoscape.memory.model.entity.MemoryVersion;
import com.mnemoscape.memory.repository.MemoryFragmentRepository;
import com.mnemoscape.memory.repository.MemoryRepository;
import com.mnemoscape.memory.repository.MemoryVersionRepository;
import com.mnemoscape.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryRepository memoryRepository;
    private final MemoryVersionRepository versionRepository;
    private final MemoryFragmentRepository fragmentRepository;
    private final AiServiceClient aiServiceClient;
    private final DriftCalculator driftCalculator;
    private final ObjectMapper objectMapper;
    private final MemoryLookup memoryLookup;
    private final MemoryGraphService memoryGraphService;
    private final GeocodingService geocodingService;

    public MemoryService(MemoryRepository memoryRepository,
                         MemoryVersionRepository versionRepository,
                         MemoryFragmentRepository fragmentRepository,
                         AiServiceClient aiServiceClient,
                         DriftCalculator driftCalculator,
                         ObjectMapper objectMapper,
                         MemoryLookup memoryLookup,
                         MemoryGraphService memoryGraphService,
                         GeocodingService geocodingService) {
        this.memoryRepository = memoryRepository;
        this.versionRepository = versionRepository;
        this.fragmentRepository = fragmentRepository;
        this.aiServiceClient = aiServiceClient;
        this.driftCalculator = driftCalculator;
        this.objectMapper = objectMapper;
        this.memoryLookup = memoryLookup;
        this.memoryGraphService = memoryGraphService;
        this.geocodingService = geocodingService;
    }

    /**
     * 创建记忆的入口。
     *
     * <p>历史实现把"基础记忆持久化"和"AI 重建 + fragment 持久化"塞进同一个
     * {@code @Transactional} 方法，导致一旦 AI 子调用抛 RuntimeException，
     * try/catch 虽然吞掉了异常，但 Spring 事务已被标记为 rollback-only，
     * 后续的 {@code memoryRepository.save()} 会以
     * {@code UnexpectedRollbackException} 形式爆出 500（这就是用户报告的
     * "构建完主题准备提交时出错"的根因）。
     *
     * <p>现在拆成三个独立的最小事务：
     * <ol>
     *   <li>{@link #persistBaseMemory(CreateMemoryRequest, String)} —
     *       事务性写入基础字段，原子且必须成功；</li>
     *   <li>{@link #enrichWithReconstruction(Memory)} —
     *       AI 增强 + fragment 落库，全 best-effort，单 fragment 失败不影响其它；</li>
     *   <li>{@link #createVersion(Memory, MemoryVersion.ChangeType, String)} —
     *       审计版本快照，best-effort，失败仅记日志。</li>
     * </ol>
     * 这样即使 AI 服务挂掉，用户依旧能拿到一个可用的 memory（带默认 scene url）；
     * 当后续 Sprint 接入 RabbitMQ 异步重建时，第二步直接换成发消息即可。
     */
    public Memory createMemory(CreateMemoryRequest request, String userId) {
        log.info("Starting memory creation process for user: {}", userId);
        Memory memory = persistBaseMemory(request, userId);
        log.info("Base memory successfully persisted. ID: {}", memory.getId());

        log.info("Beginning best-effort AI reconstruction enrichment for memory: {}", memory.getId());
        enrichWithReconstruction(memory);

        log.info("Beginning best-effort entity extraction and Neo4j graph projection for memory: {}", memory.getId());
        extractAndProjectGraph(memory);

        log.info("Creating audit snapshot version for memory: {}", memory.getId());
        createVersion(memory, MemoryVersion.ChangeType.CREATE, "Memory created");

        log.info("Memory creation process completed successfully for ID: {}", memory.getId());
        return memory;
    }

    /**
     * 调用 ai-service 提取实体并投射到 Neo4j。
     *
     * <p>整段写成 best-effort：任何一步抛异常都吞掉打 WARN —— 图谱是派生数据，
     * 主流程已经成功，没必要让"图谱挂了"反推用户看见 500。当 ai-service 的
     * 提取接口换成 LLM 后，这里的形状不变。
     */
    protected void extractAndProjectGraph(Memory memory) {
        if (memory == null || memory.getId() == null) return;
        EntityExtractionResponse entities;
        try {
            Map<String, Object> payload = Map.of(
                    "description",    memory.getDescription() == null ? "" : memory.getDescription(),
                    "memoryLocation", memory.getMemoryLocation() == null ? "" : memory.getMemoryLocation(),
                    "memoryYear",     memory.getMemoryYear() == null ? 0 : memory.getMemoryYear()
            );
            ApiResponse<EntityExtractionResponse> resp = aiServiceClient.extractEntities(payload);
            entities = resp == null ? null : resp.getData();
        } catch (Exception e) {
            log.warn("Entity extraction call failed for memory {}: {} — skipping graph write",
                    memory.getId(), e.toString());
            // 即便提取挂掉，仍然把 memory 节点 + User-OWNS 关系写进去（无实体边）
            memoryGraphService.writeMemoryGraph(memory, null);
            return;
        }

        memoryGraphService.writeMemoryGraph(memory, entities);
    }

    /*
     * 注意：不加 @Transactional。一方面同类内部调用不走 Spring 代理，注解形同虚设；
     * 另一方面 SimpleJpaRepository#save 自带 @Transactional，单条 INSERT 已经原子，
     * 显式包一层反而把 enrichWithReconstruction 的副作用拉回到外层事务，
     * 重蹈"rollback-only 把后续 save 一并炸掉"的覆辙。
     */
    protected Memory persistBaseMemory(CreateMemoryRequest request, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(401, "未通过身份认证，请重新登录");
        }
        String title = normalizeNonBlank(request.getTitle(), "Title");
        String description = normalizeNonBlank(request.getDescription(), "Description");
        if (title == null || description == null) {
            throw BizException.badRequest("Title and description are required");
        }

        Memory.PrivacyLevel privacyLevel = parsePrivacyLevel(request.getPrivacyLevel());
        if (privacyLevel == null) {
            privacyLevel = Memory.PrivacyLevel.PRIVATE;
        }

        Memory memory = Memory.builder()
                .userId(userId)
                .title(title)
                .description(description)
                .memoryYear(request.getMemoryYear())
                .memoryDate(parseMemoryDate(request.getMemoryDate()))
                .memorySeason(normalizeOptional(request.getMemorySeason()))
                .memoryTimeOfDay(normalizeOptional(request.getMemoryTimeOfDay()))
                .memoryLocation(normalizeOptional(request.getMemoryLocation()))
                .privacyLevel(privacyLevel)
                .sceneDataUrl(request.getSceneDataUrl())
                .build();
        // 写入前尝试解析坐标 — best-effort，未命中保持 null
        if (memory.getMemoryLocation() != null) {
            geocodingService.resolve(memory.getMemoryLocation()).ifPresent(c -> {
                memory.setMemoryLng(c[0]);
                memory.setMemoryLat(c[1]);
            });
        }
        return memoryRepository.save(memory);
    }

    /**
     * 调用 AI 服务做场景重建，并把结果回写到 memory + fragments。
     *
     * <p>所有失败都是 best-effort 的：AI 不可达 → 落默认 scene url；
     * fragment 形状不对 → 跳过当前一个，不影响别的；甚至连 memory 二次保存
     * 失败都只打 ERROR 不抛，保证调用方拿到的 memory 对象至少包含基础字段。
     */
    @SuppressWarnings("unchecked")
    protected void enrichWithReconstruction(Memory memory) {
        Map<String, Object> reconstruction;
        try {
            reconstruction = aiServiceClient.reconstruct(Map.of("description", memory.getDescription()));
        } catch (Exception e) {
            log.warn("AI reconstruction call failed for memory {}, using defaults: {}",
                    memory.getId(), e.toString());
            applyFallbackScene(memory);
            return;
        }

        Object dataNode = reconstruction == null ? null : reconstruction.get("data");
        if (!(dataNode instanceof Map<?, ?>)) {
            log.warn("AI reconstruction returned unexpected shape for memory {}: type={}",
                    memory.getId(), dataNode == null ? "null" : dataNode.getClass().getName());
            applyFallbackScene(memory);
            return;
        }
        Map<String, Object> data = (Map<String, Object>) dataNode;

        try {
            String sceneDataUrl = String.valueOf(data.getOrDefault("sceneDataUrl", ""));
            if (sceneDataUrl.isEmpty() || sceneDataUrl.startsWith("scene://default/")) {
                if (memory.getSceneDataUrl() == null || memory.getSceneDataUrl().startsWith("scene://default/")) {
                    memory.setSceneDataUrl("scene://default/" + memory.getId());
                }
            } else {
                memory.setSceneDataUrl(sceneDataUrl);
            }
            memory.setVisualData(objectMapper.writeValueAsString(data));
            memory.setEmotionProfile(objectMapper.writeValueAsString(
                    data.getOrDefault("emotionVector", Map.of())));
            memoryRepository.save(memory);
        } catch (Exception e) {
            log.error("Failed to persist enriched memory {}, base record still intact",
                    memory.getId(), e);
        }

        Object fragmentsNode = data.get("fragments");
        if (!(fragmentsNode instanceof List<?>)) {
            return;
        }
        for (Object frag : (List<?>) fragmentsNode) {
            if (!(frag instanceof Map<?, ?>)) {
                continue;
            }
            saveFragmentBestEffort(memory.getId(), (Map<String, Object>) frag);
        }
    }

    private void saveFragmentBestEffort(String memoryId, Map<String, Object> fragData) {
        try {
            Object position3d = fragData.get("position3d");
            String position3dJson = position3d == null
                    ? "{}"
                    : objectMapper.writeValueAsString(position3d);
            Object contentObj = fragData.get("content");
            MemoryFragment fragment = MemoryFragment.builder()
                    .memoryId(memoryId)
                    .fragmentType(String.valueOf(fragData.getOrDefault("fragmentType", "forgotten_detail")))
                    .content(contentObj == null ? null : String.valueOf(contentObj))
                    .position3d(position3dJson)
                    .triggerCondition("{\"radius\": 2.0}")
                    .build();
            fragmentRepository.save(fragment);
        } catch (Exception e) {
            log.warn("Skipped fragment for memory {} due to {}: {}",
                    memoryId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void applyFallbackScene(Memory memory) {
        if (memory.getSceneDataUrl() == null || memory.getSceneDataUrl().startsWith("scene://default/")) {
            memory.setSceneDataUrl("scene://default/" + memory.getId());
        }
        try {
            memoryRepository.save(memory);
        } catch (Exception e) {
            log.error("Even fallback scene url save failed for memory {}", memory.getId(), e);
        }
    }

    public Memory getMemory(String memoryId, String userId) {
        Memory memory = memoryLookup.findById(memoryId);
        checkAccess(memory, userId);
        return memory;
    }

    /** Cached identity lookup — write paths evict via @CacheEvict below. */
    public Memory loadMemory(String memoryId) {
        return memoryLookup.findById(memoryId);
    }

    public Page<Memory> listMemories(String userId, int page, int size) {
        return listMemories(userId, page, size, null);
    }

    public Page<Memory> listMemories(String userId, int page, int size, String privacyLevel) {
        Memory.PrivacyLevel parsedPrivacy = parsePrivacyLevel(privacyLevel);
        if (parsedPrivacy == null) {
            return memoryRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        }
        return memoryRepository.findByUserIdAndPrivacyLevelOrderByCreatedAtDesc(
                userId, parsedPrivacy, PageRequest.of(page, size));
    }

    @Transactional
    @CacheEvict(value = "memories", key = "#memoryId")
    public Memory updateMemory(String memoryId, UpdateMemoryRequest request, String userId) {
        Memory memory = getMemory(memoryId, userId);
        String title = normalizeNonBlank(request.getTitle(), "Title");
        if (title != null) memory.setTitle(title);

        String description = normalizeNonBlank(request.getDescription(), "Description");
        if (description != null) memory.setDescription(description);

        if (request.getMemoryYear() != null) memory.setMemoryYear(request.getMemoryYear());
        if (request.getMemoryDate() != null) memory.setMemoryDate(parseMemoryDate(request.getMemoryDate()));
        if (request.getMemorySeason() != null) memory.setMemorySeason(normalizeOptional(request.getMemorySeason()));
        if (request.getMemoryTimeOfDay() != null) memory.setMemoryTimeOfDay(normalizeOptional(request.getMemoryTimeOfDay()));
        if (request.getMemoryLocation() != null) {
            String normalized = normalizeOptional(request.getMemoryLocation());
            memory.setMemoryLocation(normalized);
            // 同步刷新坐标 — 解析失败保留旧值，避免擦写已有数据
            if (normalized != null) {
                java.util.Optional<double[]> resolved = geocodingService.resolve(normalized);
                if (resolved.isPresent()) {
                    double[] c = resolved.get();
                    memory.setMemoryLng(c[0]);
                    memory.setMemoryLat(c[1]);
                }
            } else {
                memory.setMemoryLng(null);
                memory.setMemoryLat(null);
            }
        }

        if (request.getPrivacyLevel() != null) {
            if (request.getPrivacyLevel().isBlank()) {
                throw BizException.badRequest("Privacy level cannot be blank");
            }
            memory.setPrivacyLevel(parsePrivacyLevel(request.getPrivacyLevel()));
        }

        if (request.getSceneDataUrl() != null) {
            memory.setSceneDataUrl(request.getSceneDataUrl());
        }

        memory = memoryRepository.save(memory);
        createVersion(memory, MemoryVersion.ChangeType.MODIFY, "Memory updated");
        return memory;
    }

    @Transactional
    @CacheEvict(value = "memories", key = "#memoryId")
    public void deleteMemory(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        memoryRepository.delete(memory);
    }

    @Transactional
    @CacheEvict(value = "memories", key = "#memoryId")
    public Memory lockMemory(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        memory.setIsLocked(true);
        memory = memoryRepository.save(memory);
        createVersion(memory, MemoryVersion.ChangeType.LOCK, "Memory locked");
        return memory;
    }

    @Transactional
    @CacheEvict(value = "memories", key = "#memoryId")
    public Memory unlockMemory(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        memory.setIsLocked(false);
        driftCalculator.calculateAndApply(memory);
        memory = memoryRepository.save(memory);
        createVersion(memory, MemoryVersion.ChangeType.LOCK, "Memory unlocked");
        return memory;
    }

    public Map<String, Object> getDriftState(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        return driftCalculator.getDriftState(memory);
    }

    public List<MemoryVersion> getVersions(String memoryId, String userId) {
        getMemory(memoryId, userId);
        return versionRepository.findByMemoryIdOrderByVersionNumberDesc(memoryId);
    }

    @Transactional
    @CacheEvict(value = "memories", key = "#memoryId")
    public Memory restoreVersion(String memoryId, int versionNumber, String userId) {
        Memory memory = getMemory(memoryId, userId);
        List<MemoryVersion> versions = versionRepository.findByMemoryIdOrderByVersionNumberDesc(memoryId);
        MemoryVersion targetVersion = versions.stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> BizException.notFound("Version", String.valueOf(versionNumber)));

        try {
            Memory restored = objectMapper.readValue(targetVersion.getSnapshotData(), Memory.class);
            restored.setId(memory.getId());
            restored.setUserId(memory.getUserId());
            restored.setCreatedAt(memory.getCreatedAt());
            restored.setUpdatedAt(LocalDateTime.now());
            memoryRepository.save(restored);
            createVersion(restored, MemoryVersion.ChangeType.RESTORE,
                    "Restored to version " + versionNumber);
            return restored;
        } catch (Exception e) {
            throw new BizException(500, "Failed to restore version: " + e.getMessage());
        }
    }

    public List<MemoryFragment> getFragments(String memoryId, String userId) {
        getMemory(memoryId, userId);
        return fragmentRepository.findByMemoryId(memoryId);
    }

    @Transactional
    public MemoryFragment discoverFragment(String fragmentId, String userId) {
        MemoryFragment fragment = fragmentRepository.findById(fragmentId)
                .orElseThrow(() -> BizException.notFound("Fragment", fragmentId));
        getMemory(fragment.getMemoryId(), userId);
        fragment.setIsDiscovered(true);
        return fragmentRepository.save(fragment);
    }

    private void createVersion(Memory memory, MemoryVersion.ChangeType type, String description) {
        try {
            int nextVersion = versionRepository.countByMemoryId(memory.getId()) + 1;
            MemoryVersion version = MemoryVersion.builder()
                    .memoryId(memory.getId())
                    .versionNumber(nextVersion)
                    .changeType(type)
                    .changeDescription(description)
                    .snapshotData(objectMapper.writeValueAsString(memory))
                    .build();
            versionRepository.save(version);
        } catch (Exception e) {
            log.error("Failed to create version for memory {}", memory.getId(), e);
        }
    }

    private void checkAccess(Memory memory, String userId) {
        if (memory.getPrivacyLevel() == Memory.PrivacyLevel.PUBLIC) return;
        if (memory.getUserId().equals(userId)) return;
        // FRIENDS privacy currently behaves as PRIVATE.
        // TODO: call auth-service friends API to allow approved friends — until
        // that integration lands, the safer default is to deny non-owners.
        throw BizException.forbidden();
    }

    private Memory.PrivacyLevel parsePrivacyLevel(String privacyLevel) {
        if (privacyLevel == null || privacyLevel.isBlank()) {
            return null;
        }
        try {
            return Memory.PrivacyLevel.valueOf(privacyLevel.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("Invalid privacy level: " + privacyLevel);
        }
    }

    private LocalDate parseMemoryDate(String memoryDate) {
        if (memoryDate == null || memoryDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(memoryDate.trim());
        } catch (DateTimeParseException ex) {
            throw BizException.badRequest("Invalid memoryDate. Expected format: YYYY-MM-DD");
        }
    }

    private String normalizeOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeNonBlank(String value, String fieldName) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw BizException.badRequest(fieldName + " cannot be blank");
        }
        return trimmed;
    }
}
