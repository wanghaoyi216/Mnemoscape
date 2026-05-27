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
    private final com.mnemoscape.memory.client.AuthServiceClient authServiceClient;
    private final DriftCalculator driftCalculator;
    private final ObjectMapper objectMapper;
    private final MemoryLookup memoryLookup;
    private final MemoryGraphService memoryGraphService;
    private final GeocodingService geocodingService;
    /** 自代理 — 用来从 createMemory 主线程触发 @Async 方法（@Async 不走 self-call 代理）。 */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private MemoryService asyncEnrichmentSelf;

    public MemoryService(MemoryRepository memoryRepository,
                         MemoryVersionRepository versionRepository,
                         MemoryFragmentRepository fragmentRepository,
                         AiServiceClient aiServiceClient,
                         com.mnemoscape.memory.client.AuthServiceClient authServiceClient,
                         DriftCalculator driftCalculator,
                         ObjectMapper objectMapper,
                         MemoryLookup memoryLookup,
                         MemoryGraphService memoryGraphService,
                         GeocodingService geocodingService) {
        this.memoryRepository = memoryRepository;
        this.versionRepository = versionRepository;
        this.fragmentRepository = fragmentRepository;
        this.aiServiceClient = aiServiceClient;
        this.authServiceClient = authServiceClient;
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
    /**
     * 创建记忆 — 主线程只做"基础持久化 + 占位 scene url + CREATE 版本快照"，
     * AI 重建（visualData / fragments / 实体抽取）异步在后台跑，让前端 POST /memories
     * 在 1s 内拿到响应，跳进详情页后再陆续看到 AI 增强结果。
     *
     * <p>之前主线程顺序跑 enrichWithReconstruction（10-30s）会让前端建造完按提交后
     * 卡很久，体验差且 axios 默认 15s 超时容易直接报错。
     */
    public Memory createMemory(CreateMemoryRequest request, String userId) {
        log.info("Starting memory creation process for user: {}", userId);
        Memory memory = persistBaseMemory(request, userId);
        log.info("Base memory successfully persisted. ID: {}", memory.getId());

        log.info("Creating audit snapshot version for memory: {}", memory.getId());
        createVersion(memory, MemoryVersion.ChangeType.CREATE, "Memory created");

        // 异步触发 AI 增强：调用方拿到结果立即返回；详情页/SceneViewer 后续会读到
        // visualData / fragments；如果用户太快进 SceneViewer，老路径的 reconstruct 兜底
        // 仍能现场补一份。
        triggerAsyncEnrichment(memory.getId());

        log.info("Memory creation process completed (async enrichment dispatched) for ID: {}", memory.getId());
        return memory;
    }

    /**
     * Spring 异步代理调用 — 通过自身代理拿到一个新线程跑 enrichWithReconstruction +
     * extractAndProjectGraph。注意：@Async 不能从同一类的内部直接调，必须经过 Spring
     * 代理，所以这里通过 ApplicationContext 拿到代理 bean。
     */
    private void triggerAsyncEnrichment(String memoryId) {
        try {
            asyncEnrichmentSelf.runEnrichmentAsync(memoryId);
        } catch (Exception e) {
            log.warn("Failed to dispatch async enrichment for {}: {}", memoryId, e.toString());
        }
    }

    /**
     * 异步增强：通过同类自代理（{@code asyncEnrichmentSelf}）确保 @Async 生效。
     * 这条流水线整段都是 best-effort：单步失败不抛错，记日志即可。
     */
    @org.springframework.scheduling.annotation.Async
    public void runEnrichmentAsync(String memoryId) {
        Memory memory;
        try {
            memory = memoryLookup.findById(memoryId);
        } catch (Exception e) {
            log.warn("[async-enrich] memory {} disappeared before enrichment: {}", memoryId, e.toString());
            return;
        }
        log.info("[async-enrich] start for memory {}", memoryId);
        try {
            enrichWithReconstruction(memory);
        } catch (Exception e) {
            log.warn("[async-enrich] reconstruction failed for {}: {}", memoryId, e.toString());
        }
        try {
            extractAndProjectGraph(memory);
        } catch (Exception e) {
            log.warn("[async-enrich] graph projection failed for {}: {}", memoryId, e.toString());
        }
        log.info("[async-enrich] done for memory {}", memoryId);
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
            // 把整段结构化上下文发给 ai-service，让 LLM 输出更 grounded（季节/时段/地点匹配）
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("description", memory.getDescription());
            if (memory.getTitle() != null) payload.put("title", memory.getTitle());
            if (memory.getMemoryYear() != null) payload.put("memoryYear", memory.getMemoryYear());
            if (memory.getMemorySeason() != null) payload.put("memorySeason", memory.getMemorySeason());
            if (memory.getMemoryTimeOfDay() != null) payload.put("memoryTimeOfDay", memory.getMemoryTimeOfDay());
            if (memory.getMemoryLocation() != null) payload.put("memoryLocation", memory.getMemoryLocation());
            reconstruction = aiServiceClient.reconstruct(payload);
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
        Memory memory = getMemory(memoryId, userId);
        return fragmentRepository.findByMemoryId(memoryId);
    }

    /**
     * 重建场景：删除现有 fragments → 重新调 ai-service /reconstruct → 写回 visualData / fragments。
     *
     * <p>用户在记忆详情页点"重建 3D 场景"按钮触发；用于把历史"假" fragment（旧规则版
     * 套模板生成的英文/无关内容）刷成 grounded 在自己记忆描述上的真实内容。
     *
     * <p>权限：{@link #getMemory(String, String)} 已经做过 checkAccess；只有 owner
     * 能触发（FRIENDS / PUBLIC 即使可读也不允许其他人改写）。
     */
    @Transactional
    public Memory regenerateScene(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        if (!memory.getUserId().equals(userId)) {
            throw BizException.forbidden();
        }
        // 1. 清掉旧 fragments（按 memoryId 整段删）
        try {
            fragmentRepository.deleteByMemoryId(memoryId);
        } catch (Exception e) {
            log.warn("Failed to clear old fragments for memory {}: {}", memoryId, e.toString());
        }
        // 2. 重新跑 reconstruct（写入 visualData / emotionProfile / 新 fragments）
        enrichWithReconstruction(memory);
        // 3. 落版本记录，方便回滚
        try {
            createVersion(memory, MemoryVersion.ChangeType.MODIFY, "Memory updated");
        } catch (Exception e) {
            log.warn("Failed to record version for regenerate: {}", e.toString());
        }
        return memory;
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
        if (memory.getPrivacyLevel() == Memory.PrivacyLevel.FRIENDS) {
            // FRIENDS 级别：调用 auth-service 探针确认 caller 与 owner 是双向已接受的好友。
            // auth-service 不可达 / 关系不存在 / 状态非 ACCEPTED → 一律拒绝（fail-closed），
            // 避免好友服务降级时把私密记忆暴露出去。
            try {
                var resp = authServiceClient.friendshipStatus(memory.getUserId(), userId);
                if (resp != null && resp.getData() != null) {
                    Object isFriend = resp.getData().get("isFriend");
                    if (Boolean.TRUE.equals(isFriend)) return;
                }
            } catch (Exception e) {
                log.warn("[MemoryService] auth-service friendship check failed; denying access. memoryId={} caller={} owner={} reason={}",
                        memory.getId(), userId, memory.getUserId(), e.getClass().getSimpleName());
            }
        }
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
