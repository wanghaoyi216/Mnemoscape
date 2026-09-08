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
    @CacheEvict(value = "publicPool", allEntries = true)
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
     * Spring 异步代理并发分发三路任务：3D重建、Neo4j关系图谱投影、Milvus向量索引。
     * 每一路采用 @Async 在 TaskExecutor 线程池中并发执行，确保关系图谱与向量在秒级瞬间更新，
     * 绝不受 3D 渲染重建的 long-tail（10-30s）延迟所阻塞。
     */
    private void triggerAsyncEnrichment(String memoryId) {
        try {
            asyncEnrichmentSelf.runEnrichmentAsync(memoryId);
        } catch (Exception e) {
            log.error("[async-enrich] Failed to dispatch reconstruction enrichment for memory {} (visualData will be missing, needs manual check): {}",
                    memoryId, e.toString());
        }
        try {
            asyncEnrichmentSelf.runGraphProjectionAsync(memoryId);
        } catch (Exception e) {
            log.error("[async-enrich] Failed to dispatch graph projection for memory {} (graph node will be missing, needs manual check): {}",
                    memoryId, e.toString());
        }
        try {
            asyncEnrichmentSelf.runVectorIndexingAsync(memoryId);
        } catch (Exception e) {
            log.error("[async-enrich] Failed to dispatch vector indexing for memory {} (vector will be missing, needs manual check): {}",
                    memoryId, e.toString());
        }
    }

    /**
     * 异步增强：仅跑 3D Scene Reconstruction，更新 visualData / fragments 并回写落库。
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
        log.info("[async-enrich] start reconstruction for memory {}", memoryId);
        try {
            enrichWithReconstruction(memory);
        } catch (Exception e) {
            log.warn("[async-enrich] reconstruction failed for {}: {}", memoryId, e.toString());
        }
        log.info("[async-enrich] done reconstruction for memory {}", memoryId);
    }

    /**
     * 异步增强：提取实体并投射到 Neo4j 关系图谱。
     */
    @org.springframework.scheduling.annotation.Async
    public void runGraphProjectionAsync(String memoryId) {
        Memory memory;
        try {
            memory = memoryLookup.findById(memoryId);
        } catch (Exception e) {
            log.warn("[async-graph] memory {} disappeared before graph projection: {}", memoryId, e.toString());
            return;
        }
        log.info("[async-graph] start graph projection for memory {}", memoryId);
        try {
            extractAndProjectGraph(memory);
        } catch (Exception e) {
            log.warn("[async-graph] graph projection failed for {}: {}", memoryId, e.toString());
        }
        log.info("[async-graph] done graph projection for memory {}", memoryId);
    }

    /**
     * 异步增强：把记忆写入 Milvus 向量库。
     */
    @org.springframework.scheduling.annotation.Async
    public void runVectorIndexingAsync(String memoryId) {
        Memory memory;
        try {
            memory = memoryLookup.findById(memoryId);
        } catch (Exception e) {
            log.warn("[async-vector] memory {} disappeared before vector indexing: {}", memoryId, e.toString());
            return;
        }
        log.info("[async-vector] start vector indexing for memory {}", memoryId);
        try {
            indexMemoryVector(memory);
        } catch (Exception e) {
            log.warn("[async-vector] vector indexing failed for {}: {}", memoryId, e.toString());
        }
        log.info("[async-vector] done vector indexing for memory {}", memoryId);
    }

    /**
     * 把记忆写入 Milvus 向量库（经 ai-service 的 /vector/index）。
     *
     * <p>best-effort：ai-service 即便 Embedding / Milvus 不可用也返回 200，
     * 所以这里只需吞掉 Feign 自身的网络异常。索引让 AI 检索 / 共鸣大厅 / RAG
     * 能用真实稠密向量召回；失败时这些功能各自有关键词降级，主流程不受影响。
     */
    protected void indexMemoryVector(Memory memory) {
        if (memory == null || memory.getId() == null) return;
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("memoryId", memory.getId());
            payload.put("userId", memory.getUserId());
            payload.put("title", memory.getTitle() == null ? "" : memory.getTitle());
            payload.put("location", memory.getMemoryLocation() == null ? "" : memory.getMemoryLocation());
            payload.put("year", memory.getMemoryYear() == null ? 0 : memory.getMemoryYear());
            payload.put("description", memory.getDescription() == null ? "" : memory.getDescription());
            payload.put("privacy", memory.getPrivacyLevel() == null ? "PRIVATE" : memory.getPrivacyLevel().name());
            aiServiceClient.indexVector(payload);
            log.info("[vector-index] dispatched index for memory {}", memory.getId());
        } catch (Exception e) {
            log.warn("[vector-index] index call failed for memory {}: {}", memory.getId(), e.toString());
        }
    }

    /**
     * 坐标回填：扫描所有 memoryLocation 非空但 memoryLat/memoryLng 为 null 的记忆，
     * 重新跑 GeocodingService 解析坐标并写入数据库。
     *
     * <p>用于：1) 历史记忆在 geocoder 开启前创建，坐标为 null；
     *          2) 切换 geocoder 策略后给旧数据补坐标。
     * 逐条同步处理（geocoder 本地 anchor 表 O(1)，远程 Nominatim 有速率限制），
     * 返回 {scanned, resolved, skipped, failed, limit}。skipped=已有坐标/无地名的正常跳过;
     * failed=geocoding 返回空或抛异常的失败数,管理员可据此看到真实失败率。
     *
     * @param limit 单次最多处理多少条（防止一次性全表扫描）
     */
    public Map<String, Object> backfillGeocoords(int limit) {
        int capped = Math.max(1, Math.min(limit, 5000));
        org.springframework.data.domain.Page<Memory> page = memoryRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, capped));
        int scanned = 0;
        int resolved = 0;
        int skipped = 0;
        int failed = 0;
        for (Memory m : page.getContent()) {
            scanned++;
            // 已有坐标的跳过
            if (m.getMemoryLng() != null && m.getMemoryLat() != null) {
                skipped++;
                continue;
            }
            // 没有地名文本的跳过
            if (m.getMemoryLocation() == null || m.getMemoryLocation().isBlank()) {
                skipped++;
                continue;
            }
            try {
                java.util.Optional<double[]> coords = geocodingService.resolve(m.getMemoryLocation());
                if (coords.isPresent()) {
                    m.setMemoryLng(coords.get()[0]);
                    m.setMemoryLat(coords.get()[1]);
                    memoryRepository.save(m);
                    resolved++;
                    log.info("[geocoords-backfill] resolved memory {} location='{}' → [{},{}]",
                            m.getId(), m.getMemoryLocation(), coords.get()[0], coords.get()[1]);
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.warn("[geocoords-backfill] failed for memory {}: {}", m.getId(), e.toString());
                failed++;
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("scanned", scanned);
        result.put("resolved", resolved);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("limit", capped);
        result.put("total", page.getTotalElements());
        log.info("[geocoords-backfill] scanned={} resolved={} skipped={} failed={} (total={})",
                scanned, resolved, skipped, failed, page.getTotalElements());
        return result;
    }

    /**
     * 向量回填（管理员触发）：把现有记忆批量重新索引进 Milvus。
     *
     * <p>用于：1) 首次接入向量检索后，给历史记忆补索引；2) 切换 embedding 模型 /
     * 维度后重建 collection。逐条调 {@link #indexMemoryVector}（best-effort），
     * 返回 {@code {total, dispatched}} 统计。
     *
     * @param limit 单次最多处理多少条（防止一次性把全表灌进上游 embedding）
     */
    public Map<String, Object> backfillVectors(int limit) {
        int capped = Math.max(1, Math.min(limit, 2000));
        org.springframework.data.domain.Page<Memory> page = memoryRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, capped));
        int dispatched = 0;
        for (Memory m : page.getContent()) {
            try {
                indexMemoryVector(m);
                dispatched++;
            } catch (Exception e) {
                log.warn("[vector-backfill] failed for memory {}: {}", m.getId(), e.toString());
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", page.getTotalElements());
        result.put("dispatched", dispatched);
        result.put("limit", capped);
        log.info("[vector-backfill] dispatched {} of {} total memories", dispatched, page.getTotalElements());
        return result;
    }

    /**
     * 历史 visualData 清洗（管理员触发）：扫描 visualData 为 null/空 或仍是旧英文模板的记忆，
     * 异步重新跑 reconstruction，让 SceneViewer 不再展示"假"场景。
     *
     * <p>判定"需要清洗"：
     * <ul>
     *   <li>visualData 为 null / 空白；或</li>
     *   <li>visualData 含旧规则版英文模板指纹（如 environment 仍是英文 snake_case 关键词）。</li>
     * </ul>
     * 逐条通过自代理异步 enrich（不阻塞请求），返回 {scanned, dispatched, limit}。
     *
     * @param limit 单次最多处理多少条
     */
    public Map<String, Object> cleanupVisualData(int limit) {
        int capped = Math.max(1, Math.min(limit, 1000));
        org.springframework.data.domain.Page<Memory> page = memoryRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, capped));
        int scanned = 0;
        int dispatched = 0;
        for (Memory m : page.getContent()) {
            scanned++;
            if (!needsVisualDataCleanup(m)) continue;
            try {
                asyncEnrichmentSelf.runEnrichmentAsync(m.getId());
                dispatched++;
            } catch (Exception e) {
                log.warn("[visualdata-cleanup] dispatch failed for memory {}: {}", m.getId(), e.toString());
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("scanned", scanned);
        result.put("dispatched", dispatched);
        result.put("limit", capped);
        result.put("total", page.getTotalElements());
        log.info("[visualdata-cleanup] scanned={} dispatched={} (total={})",
                scanned, dispatched, page.getTotalElements());
        return result;
    }

    /** 判定一条记忆的 visualData 是否需要重建（null/空 或 旧英文模板指纹）。 */
    private boolean needsVisualDataCleanup(Memory m) {
        String vd = m.getVisualData();
        if (vd == null || vd.isBlank()) return true;
        // 旧规则版英文模板指纹：environment 取自固定英文 snake_case 词表
        String lower = vd.toLowerCase(java.util.Locale.ROOT);
        String[] legacyMarkers = {
                "summer_courtyard", "snowy_landscape", "outdoor_courtyard",
                "warm_sunset", "a moment of pure childhood joy",
                "forgotten_detail", "emotion_flashback"
        };
        for (String marker : legacyMarkers) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * 历史 Fragments 批量重建（管理员触发）：扫描 legacy / 英文 / 缺失 fragments 的记忆，
     * 异步清除并重新跑 reconstruction，生成 grounded 中文 fragments。
     *
     * @param limit 单次最多处理多少条
     */
    public Map<String, Object> rebuildFragments(int limit) {
        int capped = Math.max(1, Math.min(limit, 1000));
        org.springframework.data.domain.Page<Memory> page = memoryRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, capped));
        int scanned = 0;
        int dispatched = 0;
        for (Memory m : page.getContent()) {
            scanned++;
            if (!needsFragmentRebuild(m)) continue;
            try {
                asyncEnrichmentSelf.runFragmentRebuildAsync(m.getId());
                dispatched++;
            } catch (Exception e) {
                log.warn("[fragment-rebuild] dispatch failed for memory {}: {}", m.getId(), e.toString());
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("scanned", scanned);
        result.put("dispatched", dispatched);
        result.put("limit", capped);
        result.put("total", page.getTotalElements());
        log.info("[fragment-rebuild] scanned={} dispatched={} (total={})",
                scanned, dispatched, page.getTotalElements());
        return result;
    }

    /** 判定一条记忆的 fragments 是否需要重建（符合 needsVisualDataCleanup，或包含英文 mock 特征词）。 */
    private boolean needsFragmentRebuild(Memory m) {
        if (needsVisualDataCleanup(m)) return true;
        List<MemoryFragment> fragments = fragmentRepository.findByMemoryId(m.getId());
        if (fragments.isEmpty()) return true;
        for (MemoryFragment f : fragments) {
            String desc = f.getContent();
            if (desc == null || desc.isBlank()) return true;
            String lower = desc.toLowerCase(java.util.Locale.ROOT);
            String[] legacyMarkers = {
                    "a moment of pure", "playground", "grandma's kitchen",
                    "childhood joy", "forgotten toy", "old photograph",
                    "wild mushrooms", "quiet peace", "frost etched",
                    "woolen scarf", "boot prints", "icicles shimmering",
                    "hush of stillness", "laughter echoes", "attractor"
            };
            for (String marker : legacyMarkers) {
                if (lower.contains(marker)) return true;
            }
        }
        return false;
    }

    /** 异步执行单条记忆的碎片清空与重新构建 */
    @org.springframework.scheduling.annotation.Async
    public void runFragmentRebuildAsync(String memoryId) {
        Memory memory;
        try {
            memory = memoryLookup.findById(memoryId);
        } catch (Exception e) {
            log.warn("[fragment-rebuild-async] memory {} disappeared: {}", memoryId, e.toString());
            return;
        }
        log.info("[fragment-rebuild-async] start for memory {}", memoryId);
        try {
            // 1. 清空旧 fragments 记录
            fragmentRepository.deleteByMemoryId(memoryId);
            // 2. 重新进行 AI 场景重建及 grounded 碎片生成
            enrichWithReconstruction(memory);
            // 3. 记录修改版本
            createVersion(memory, MemoryVersion.ChangeType.MODIFY, "Fragments batch rebuilt");
        } catch (Exception e) {
            log.warn("[fragment-rebuild-async] failed for memory {}: {}", memoryId, e.toString());
        }
        log.info("[fragment-rebuild-async] done for memory {}", memoryId);
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
        // 写入前确定坐标：优先采用前端提交的精确 GPS / 正向地理编码坐标（街道级），
        // 仅在缺失或越界时才退回 GeocodingService 按地名解析（城市中心点级）。
        Double preciseLng = request.getMemoryLng();
        Double preciseLat = request.getMemoryLat();
        if (isValidCoord(preciseLng, preciseLat)) {
            memory.setMemoryLng(preciseLng);
            memory.setMemoryLat(preciseLat);
        } else if (memory.getMemoryLocation() != null) {
            geocodingService.resolve(memory.getMemoryLocation()).ifPresent(c -> {
                memory.setMemoryLng(c[0]);
                memory.setMemoryLat(c[1]);
            });
        }
        return memoryRepository.save(memory);
    }

    /** 经纬度范围校验：lng∈[-180,180]，lat∈[-90,90]，且非 (0,0) 哨兵空值。 */
    private boolean isValidCoord(Double lng, Double lat) {
        if (lng == null || lat == null) return false;
        if (lng.isNaN() || lat.isNaN()) return false;
        if (lng < -180 || lng > 180 || lat < -90 || lat > 90) return false;
        // (0,0) 在几内亚湾，几乎不可能是真实记忆点，视作未填
        return !(Math.abs(lng) < 1e-7 && Math.abs(lat) < 1e-7);
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

    public Map<String, Object> getMemoryGraphData(String id, String userId) {
        Memory memory = getMemory(id, userId);
        Map<String, Object> graph = memoryGraphService.getMemoryGraph(id, userId);
        Map<String, Object> result = new java.util.HashMap<>(graph);
        result.put("centerTitle", memory.getTitle());
        return result;
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

    /**
     * 跨用户公共记忆池——专供 resonance-service 真实化检索。
     *
     * <p>这是一个高频读、低频写的热路径：每个用户每次共鸣搜索都会拉一遍全量公共池，
     * 而公共记忆的增删频率远低于读取。因此用 Caffeine 缓存 60s（{@code publicPool}
     * cache，见 application.yml），key = {@code 调用方userId + ':' + limit}。
     * 任何记忆写操作（创建/更新/删除/改隐私）都会 {@code @CacheEvict allEntries}
     * 把整个池清空，保证不会读到陈旧的公共记忆。
     *
     * <p>不缓存单用户自己的列表（{@link #listMemories}）——那条路径每个用户只看自己，
     * 命中率低且写后立即要看到，缓存收益不划算。
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "publicPool", key = "#excludeUserId + ':' + #limit", sync = true)
    public List<Memory> getPublicPool(String excludeUserId, int limit) {
        int safeLimit = Math.max(10, Math.min(limit, 500));
        return memoryRepository.findPublicPoolExcludingUser(
                excludeUserId, PageRequest.of(0, safeLimit));
    }

    @Transactional
    @org.springframework.cache.annotation.Caching(evict = {
            @CacheEvict(value = "memories", key = "#memoryId"),
            @CacheEvict(value = "publicPool", allEntries = true)
    })
    public Memory updateMemory(String memoryId, UpdateMemoryRequest request, String userId) {
        Memory memory = getMemory(memoryId, userId);
        checkOwner(memory, userId);
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
            // 坐标刷新优先级：前端提交的精确 GPS / 正向地理编码坐标 > 按地名 anchor 解析。
            // 解析失败保留旧值，避免擦写已有数据。
            if (normalized != null) {
                if (isValidCoord(request.getMemoryLng(), request.getMemoryLat())) {
                    memory.setMemoryLng(request.getMemoryLng());
                    memory.setMemoryLat(request.getMemoryLat());
                } else {
                    java.util.Optional<double[]> resolved = geocodingService.resolve(normalized);
                    if (resolved.isPresent()) {
                        double[] c = resolved.get();
                        memory.setMemoryLng(c[0]);
                        memory.setMemoryLat(c[1]);
                    }
                }
            } else {
                memory.setMemoryLng(null);
                memory.setMemoryLat(null);
            }
        } else if (isValidCoord(request.getMemoryLng(), request.getMemoryLat())) {
            // 仅更新坐标（地名不变）：例如用户在不改文字的情况下重新精确定位
            memory.setMemoryLng(request.getMemoryLng());
            memory.setMemoryLat(request.getMemoryLat());
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
    @org.springframework.cache.annotation.Caching(evict = {
            @CacheEvict(value = "memories", key = "#memoryId"),
            @CacheEvict(value = "publicPool", allEntries = true)
    })
    public void deleteMemory(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        checkOwner(memory, userId);
        memoryRepository.delete(memory);
        // best-effort 清理向量库残留，避免删除后 AI 检索仍召回旧记忆。
        try {
            aiServiceClient.deleteVector(memoryId);
        } catch (Exception e) {
            log.warn("[vector-index] delete call failed for memory {}: {}", memoryId, e.toString());
        }
    }

    @Transactional
    @org.springframework.cache.annotation.Caching(evict = {
            @CacheEvict(value = "memories", key = "#memoryId"),
            @CacheEvict(value = "publicPool", allEntries = true)
    })
    public Memory lockMemory(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        checkOwner(memory, userId);
        memory.setIsLocked(true);
        memory = memoryRepository.save(memory);
        createVersion(memory, MemoryVersion.ChangeType.LOCK, "Memory locked");
        return memory;
    }

    @Transactional
    @org.springframework.cache.annotation.Caching(evict = {
            @CacheEvict(value = "memories", key = "#memoryId"),
            @CacheEvict(value = "publicPool", allEntries = true)
    })
    public Memory unlockMemory(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        checkOwner(memory, userId);
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
    @org.springframework.cache.annotation.Caching(evict = {
            @CacheEvict(value = "memories", key = "#memoryId"),
            @CacheEvict(value = "publicPool", allEntries = true)
    })
    public Memory restoreVersion(String memoryId, int versionNumber, String userId) {
        Memory memory = getMemory(memoryId, userId);
        checkOwner(memory, userId);
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
    public Memory regenerateScene(String memoryId, String userId) {
        Memory memory = getMemory(memoryId, userId);
        if (!memory.getUserId().equals(userId)) {
            throw BizException.forbidden();
        }
        // 1. 清掉旧 fragments（按 memoryId 整段删）
        try {
            fragmentRepository.deleteByMemoryId(memoryId);
        } catch (Exception e) {
            log.error("Failed to clear old fragments for memory {}: {}", memoryId, e.toString());
            throw new BizException(500, "清除旧场景碎片失败,请重试场景重建");
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
            // 版本快照是回滚兜底,失败不阻断主流程,但会丢失历史版本(监控会抓 ERROR 日志)
        } catch (Exception e) {
            log.error("Failed to create version for memory {}", memory.getId(), e);
        }
    }

    private void checkOwner(Memory memory, String userId) {
        if (memory.getUserId() == null || !memory.getUserId().equals(userId)) {
            throw BizException.forbidden();
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
