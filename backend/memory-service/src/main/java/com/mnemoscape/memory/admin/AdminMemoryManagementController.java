package com.mnemoscape.memory.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.dto.PageResult;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.repository.MemoryRepository;
import com.mnemoscape.memory.repository.MemoryFragmentRepository;
import com.mnemoscape.memory.repository.MemoryVersionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 — 记忆管理（数据表格 + 批量操作）。
 *
 * <p>提供分页查询，搜索过滤（按标题 / 用户 ID / 隐私级别 / 锁定状态），
 * 批量删除，批量更新隐私级别 / 锁定状态。所有操作需要 ROLE_ADMIN
 * （由 SecurityConfig 校验）。
 *
 * <p>与公开的 /admin/stats/* 聚合端点不同，这里返回完整 Memory 字段（包含 title /
 * description / userId）—— 这是管理面板按设计就需要看到的运营信息。
 */
@RestController
@RequestMapping("/api/v1/admin/memories")
public class AdminMemoryManagementController {

    private static final Logger log = LoggerFactory.getLogger(AdminMemoryManagementController.class);
    private static final Logger audit = LoggerFactory.getLogger("admin-audit");

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 500;

    private final MemoryRepository memoryRepository;
    private final MemoryFragmentRepository fragmentRepository;
    private final MemoryVersionRepository versionRepository;
    private final com.mnemoscape.memory.service.MemoryService memoryService;

    public AdminMemoryManagementController(MemoryRepository memoryRepository,
                                           MemoryFragmentRepository fragmentRepository,
                                           MemoryVersionRepository versionRepository,
                                           com.mnemoscape.memory.service.MemoryService memoryService) {
        this.memoryRepository = memoryRepository;
        this.fragmentRepository = fragmentRepository;
        this.versionRepository = versionRepository;
        this.memoryService = memoryService;
    }

    /** 严格白名单 DTO — 包含管理员看得到的核心运营字段，不携带 visualData/audioData/emotionProfile。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminMemoryRow(
            String id,
            String userId,
            String title,
            String description,
            Integer memoryYear,
            String memoryDate,
            String memorySeason,
            String memoryTimeOfDay,
            String memoryLocation,
            Double memoryLng,
            Double memoryLat,
            String privacyLevel,
            Boolean isLocked,
            Double fadeLevel,
            String createdAt,
            String updatedAt
    ) {}

    private static AdminMemoryRow toRow(Memory m) {
        return new AdminMemoryRow(
                m.getId(),
                m.getUserId(),
                m.getTitle(),
                m.getDescription() == null || m.getDescription().length() <= 200
                        ? m.getDescription()
                        : m.getDescription().substring(0, 200) + "…",
                m.getMemoryYear(),
                m.getMemoryDate() == null ? null : m.getMemoryDate().toString(),
                m.getMemorySeason(),
                m.getMemoryTimeOfDay(),
                m.getMemoryLocation(),
                m.getMemoryLng(),
                m.getMemoryLat(),
                m.getPrivacyLevel() == null ? null : m.getPrivacyLevel().name(),
                m.getIsLocked(),
                m.getFadeLevel(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().toString(),
                m.getUpdatedAt() == null ? null : m.getUpdatedAt().toString()
        );
    }

    /**
     * GET /api/v1/admin/memories — paginated list with filters.
     *
     * <p>Filters (all optional):
     * <ul>
     *   <li>{@code search} — fuzzy match on title (case-insensitive)</li>
     *   <li>{@code userId} — exact owner</li>
     *   <li>{@code privacyLevel} — PRIVATE / FRIENDS / PUBLIC</li>
     *   <li>{@code locked} — true/false</li>
     * </ul>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<AdminMemoryRow>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String privacyLevel,
            @RequestParam(required = false) Boolean locked,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            HttpServletRequest req) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);

        Sort sort = "asc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        PageRequest pageReq = PageRequest.of(safePage, safeSize, sort);

        Specification<Memory> spec = (root, query, cb) -> cb.conjunction();
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("title")), pattern));
        }
        if (userId != null && !userId.isBlank()) {
            String uid = userId.trim();
            spec = spec.and((root, q, cb) -> cb.equal(root.get("userId"), uid));
        }
        if (privacyLevel != null && !privacyLevel.isBlank()) {
            try {
                Memory.PrivacyLevel pl = Memory.PrivacyLevel.valueOf(privacyLevel.trim().toUpperCase());
                spec = spec.and((root, q, cb) -> cb.equal(root.get("privacyLevel"), pl));
            } catch (IllegalArgumentException ex) {
                throw new BizException(400, "INVALID_PRIVACY_LEVEL");
            }
        }
        if (locked != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("isLocked"), locked));
        }

        Page<Memory> result = memoryRepository.findAll(spec, pageReq);
        List<AdminMemoryRow> rows = result.getContent().stream()
                .map(AdminMemoryManagementController::toRow)
                .toList();
        logAccess(req, "/api/v1/admin/memories", "list", 200);
        return ResponseEntity.ok(ApiResponse.success(
                PageResult.of(rows, result.getTotalElements(), safePage, safeSize)));
    }

    /** 批量删除：传入 ids 数组（最多 500），删除记忆同时清空 fragments / versions。 */
    @PostMapping("/batch-delete")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDelete(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "IDS_REQUIRED");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "BATCH_TOO_LARGE");
        }
        int deleted = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            try {
                fragmentRepository.deleteByMemoryId(id);
                versionRepository.deleteByMemoryId(id);
                memoryRepository.deleteById(id);
                deleted++;
            } catch (Exception e) {
                log.warn("[admin] batch-delete failed for memory {}: {}", id, e.toString());
                failed.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("failed", failed);
        logAccess(req, "/api/v1/admin/memories/batch-delete", "deleted=" + deleted, 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 批量更改隐私级别。body: {ids: [], privacyLevel: "PUBLIC"|"FRIENDS"|"PRIVATE"} */
    @PostMapping("/batch-privacy")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchUpdatePrivacy(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        String privacy = (String) body.get("privacyLevel");
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "IDS_REQUIRED");
        }
        if (privacy == null || privacy.isBlank()) {
            throw new BizException(400, "PRIVACY_LEVEL_REQUIRED");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "BATCH_TOO_LARGE");
        }
        Memory.PrivacyLevel target;
        try {
            target = Memory.PrivacyLevel.valueOf(privacy.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException(400, "INVALID_PRIVACY_LEVEL");
        }
        int updated = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            var opt = memoryRepository.findById(id);
            if (opt.isPresent()) {
                Memory m = opt.get();
                m.setPrivacyLevel(target);
                m.setUpdatedAt(LocalDateTime.now());
                memoryRepository.save(m);
                updated++;
            }
        }
        Map<String, Object> result = Map.of("updated", updated, "privacyLevel", target.name());
        logAccess(req, "/api/v1/admin/memories/batch-privacy",
                "updated=" + updated + " level=" + target.name(), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 批量锁定 / 解锁。body: {ids: [], locked: true|false} */
    @PostMapping("/batch-lock")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchLock(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        Boolean locked = (Boolean) body.get("locked");
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "IDS_REQUIRED");
        }
        if (locked == null) {
            throw new BizException(400, "LOCKED_FLAG_REQUIRED");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "BATCH_TOO_LARGE");
        }
        int updated = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            var opt = memoryRepository.findById(id);
            if (opt.isPresent()) {
                Memory m = opt.get();
                m.setIsLocked(locked);
                m.setUpdatedAt(LocalDateTime.now());
                memoryRepository.save(m);
                updated++;
            }
        }
        Map<String, Object> result = Map.of("updated", updated, "locked", locked);
        logAccess(req, "/api/v1/admin/memories/batch-lock",
                "updated=" + updated + " locked=" + locked, 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 删除单条：等价于 batchDelete 单元素，便于按行删除时简化前端调用。 */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteOne(@PathVariable String id, HttpServletRequest req) {
        if (id == null || id.isBlank()) throw new BizException(400, "ID_REQUIRED");
        if (!memoryRepository.existsById(id)) throw new BizException(404, "MEMORY_NOT_FOUND");
        try {
            fragmentRepository.deleteByMemoryId(id);
            versionRepository.deleteByMemoryId(id);
            memoryRepository.deleteById(id);
        } catch (Exception e) {
            log.error("[admin] delete failed for memory {}", id, e);
            throw new BizException(500, "DELETE_FAILED");
        }
        logAccess(req, "/api/v1/admin/memories/" + id, "deleted", 200);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }

    /** 行内编辑：单条更新隐私级别 / 锁定。body 字段全部 optional。 */
    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<AdminMemoryRow>> patchOne(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        var opt = memoryRepository.findById(id);
        if (opt.isEmpty()) throw new BizException(404, "MEMORY_NOT_FOUND");
        Memory m = opt.get();
        if (body.containsKey("privacyLevel")) {
            String p = (String) body.get("privacyLevel");
            if (p != null && !p.isBlank()) {
                try {
                    m.setPrivacyLevel(Memory.PrivacyLevel.valueOf(p.trim().toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    throw new BizException(400, "INVALID_PRIVACY_LEVEL");
                }
            }
        }
        if (body.containsKey("locked")) {
            Object v = body.get("locked");
            if (v instanceof Boolean b) m.setIsLocked(b);
        }
        if (body.containsKey("fadeLevel")) {
            Object v = body.get("fadeLevel");
            if (v instanceof Number n) m.setFadeLevel(n.doubleValue());
        }
        m.setUpdatedAt(LocalDateTime.now());
        memoryRepository.save(m);
        logAccess(req, "/api/v1/admin/memories/" + id, "patched", 200);
        return ResponseEntity.ok(ApiResponse.success(toRow(m)));
    }

    /**
     * 坐标回填：给所有 memoryLocation 非空但 memoryLat/memoryLng 为 null 的记忆
     * 重新跑 GeocodingService 解析坐标。
     * body（可选）: {limit: 1000}。返回 {scanned, resolved, skipped, limit}。
     */
    @PostMapping("/backfill-geocoords")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfillGeocoords(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest req) {
        int limit = 1000;
        if (body != null && body.get("limit") instanceof Number n) {
            limit = n.intValue();
        }
        Map<String, Object> result = memoryService.backfillGeocoords(limit);
        logAccess(req, "/api/v1/admin/memories/backfill-geocoords",
                "resolved=" + result.get("resolved"), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 向量回填：把现有记忆批量重新索引进 Milvus（接入向量检索后给历史数据补索引，
     * 或切换 embedding 模型 / 维度后重建）。
     * body（可选）: {limit: 500}。逐条 best-effort，不阻塞；返回 {total, dispatched, limit}。
     */
    @PostMapping("/backfill-vectors")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfillVectors(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest req) {
        int limit = 500;
        if (body != null && body.get("limit") instanceof Number n) {
            limit = n.intValue();
        }
        Map<String, Object> result = memoryService.backfillVectors(limit);
        logAccess(req, "/api/v1/admin/memories/backfill-vectors",
                "dispatched=" + result.get("dispatched"), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 历史 visualData 清洗：批量重建 visualData 为 null/空 或仍是旧英文模板的记忆。
     * body（可选）: {limit: 500}。异步逐条 best-effort；返回 {scanned, dispatched, limit, total}。
     */
    @PostMapping("/cleanup-visualdata")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cleanupVisualData(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest req) {
        int limit = 500;
        if (body != null && body.get("limit") instanceof Number n) {
            limit = n.intValue();
        }
        Map<String, Object> result = memoryService.cleanupVisualData(limit);
        logAccess(req, "/api/v1/admin/memories/cleanup-visualdata",
                "dispatched=" + result.get("dispatched"), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 历史 Fragments 批量重建：扫描 legacy / 英文 / 缺失 fragments 的记忆，
     * 异步清除并重新跑 reconstruction，生成 grounded 中文 fragments。
     * body（可选）: {limit: 500}。
     */
    @PostMapping("/rebuild-fragments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rebuildFragments(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest req) {
        int limit = 500;
        if (body != null && body.get("limit") instanceof Number n) {
            limit = n.intValue();
        }
        Map<String, Object> result = memoryService.rebuildFragments(limit);
        logAccess(req, "/api/v1/admin/memories/rebuild-fragments",
                "dispatched=" + result.get("dispatched"), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private void logAccess(HttpServletRequest req, String path, String detail, int status) {
        try {
            String adminUserId = req.getHeader("X-User-Id");
            audit.info("admin-management adminUserId={} path={} detail={} status={}",
                    adminUserId, path, detail, status);
        } catch (Exception ignore) {
            // never break the response path because of audit logging
        }
    }
}
