package com.mnemoscape.memory.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.dto.PageResult;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.memory.admin.AdminMemoryManagementService.BatchDeleteResult;
import com.mnemoscape.memory.admin.AdminMemoryManagementService.BatchUpdateResult;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.service.MemoryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
 *
 * <p>Controller 只做 HTTP 协议层:参数解析 + HTTP 格式校验 + 调 Service + audit 日志 + 包装响应。
 * 业务逻辑与事务边界在 {@link AdminMemoryManagementService}。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/memories")
public class AdminMemoryManagementController {

    private static final Logger audit = LoggerFactory.getLogger("admin-audit");

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 500;

    private final AdminMemoryManagementService adminMemoryManagementService;
    private final MemoryService memoryService;

    public AdminMemoryManagementController(AdminMemoryManagementService adminMemoryManagementService,
                                           MemoryService memoryService) {
        this.adminMemoryManagementService = adminMemoryManagementService;
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
        // HTTP 格式校验
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);

        // 业务校验:privacyLevel 枚举合法性(快速失败,不进 Service 查询)
        if (privacyLevel != null && !privacyLevel.isBlank()) {
            try {
                Memory.PrivacyLevel.valueOf(privacyLevel.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BizException(400, "INVALID_PRIVACY_LEVEL");
            }
        }

        Page<Memory> result = adminMemoryManagementService.list(
                safePage, safeSize, sortBy, sortDir, search, userId, privacyLevel, locked);
        List<AdminMemoryRow> rows = result.getContent().stream()
                .map(AdminMemoryManagementController::toRow)
                .toList();
        logAccess(req, "/api/v1/admin/memories", "list", 200);
        return ResponseEntity.ok(ApiResponse.success(
                PageResult.of(rows, result.getTotalElements(), safePage, safeSize)));
    }

    /** 批量删除：传入 ids 数组（最多 500），删除记忆同时清空 fragments / versions。 */
    @PostMapping("/batch-delete")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDelete(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        // HTTP 格式校验
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "IDS_REQUIRED");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "BATCH_TOO_LARGE");
        }
        BatchDeleteResult r = adminMemoryManagementService.batchDelete(ids);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", r.deleted());
        result.put("failed", r.failed());
        logAccess(req, "/api/v1/admin/memories/batch-delete", "deleted=" + r.deleted(), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 批量更改隐私级别。body: {ids: [], privacyLevel: "PUBLIC"|"FRIENDS"|"PRIVATE"} */
    @PostMapping("/batch-privacy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchUpdatePrivacy(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        String privacy = (String) body.get("privacyLevel");
        // HTTP 格式校验
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "IDS_REQUIRED");
        }
        if (privacy == null || privacy.isBlank()) {
            throw new BizException(400, "PRIVACY_LEVEL_REQUIRED");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "BATCH_TOO_LARGE");
        }
        // 业务校验:枚举合法性(快速失败)
        try {
            Memory.PrivacyLevel.valueOf(privacy.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException(400, "INVALID_PRIVACY_LEVEL");
        }
        BatchUpdateResult r = adminMemoryManagementService.batchUpdatePrivacy(ids, privacy);
        Map<String, Object> result = Map.of("updated", r.updated(), "privacyLevel", r.privacyLevel());
        logAccess(req, "/api/v1/admin/memories/batch-privacy",
                "updated=" + r.updated() + " level=" + r.privacyLevel(), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 批量锁定 / 解锁。body: {ids: [], locked: true|false} */
    @PostMapping("/batch-lock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchLock(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        Boolean locked = (Boolean) body.get("locked");
        // HTTP 格式校验
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "IDS_REQUIRED");
        }
        if (locked == null) {
            throw new BizException(400, "LOCKED_FLAG_REQUIRED");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "BATCH_TOO_LARGE");
        }
        BatchUpdateResult r = adminMemoryManagementService.batchLock(ids, locked);
        Map<String, Object> result = Map.of("updated", r.updated(), "locked", r.locked());
        logAccess(req, "/api/v1/admin/memories/batch-lock",
                "updated=" + r.updated() + " locked=" + r.locked(), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 删除单条：等价于 batchDelete 单元素，便于按行删除时简化前端调用。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteOne(@PathVariable String id, HttpServletRequest req) {
        // HTTP 格式校验
        if (id == null || id.isBlank()) throw new BizException(400, "ID_REQUIRED");
        adminMemoryManagementService.deleteOne(id);
        logAccess(req, "/api/v1/admin/memories/" + id, "deleted", 200);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }

    /** 行内编辑：单条更新隐私级别 / 锁定。body 字段全部 optional。 */
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminMemoryRow>> patchOne(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        // 业务校验:privacyLevel 枚举合法性(若提供)
        if (body.containsKey("privacyLevel")) {
            String p = (String) body.get("privacyLevel");
            if (p != null && !p.isBlank()) {
                try {
                    Memory.PrivacyLevel.valueOf(p.trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                    throw new BizException(400, "INVALID_PRIVACY_LEVEL");
                }
            }
        }
        Memory m = adminMemoryManagementService.patchOne(id, body);
        logAccess(req, "/api/v1/admin/memories/" + id, "patched", 200);
        return ResponseEntity.ok(ApiResponse.success(toRow(m)));
    }

    /**
     * 批量查询记忆详情：供管理员面板共鸣概览图渲染节点标题（R12.3 / R15.1 对齐）。
     * body: {ids: ["id1", "id2", ...]}
     */
    @PostMapping("/batch-details")
    public ResponseEntity<ApiResponse<Map<String, AdminMemoryRow>>> getBatchDetails(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        // HTTP 格式校验
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "IDS_REQUIRED");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "BATCH_TOO_LARGE");
        }
        Map<String, Memory> memories = adminMemoryManagementService.getBatchDetails(ids);
        Map<String, AdminMemoryRow> result = new HashMap<>();
        for (Map.Entry<String, Memory> e : memories.entrySet()) {
            result.put(e.getKey(), toRow(e.getValue()));
        }
        logAccess(req, "/api/v1/admin/memories/batch-details", "fetched=" + result.size(), 200);
        return ResponseEntity.ok(ApiResponse.success(result));
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
