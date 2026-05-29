package com.mnemoscape.resonance.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.dto.PageResult;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.resonance.model.entity.ResonanceSpace;
import com.mnemoscape.resonance.repository.ResonanceSpaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 — 灵魂共鸣关系管理（数据表格 + 批量操作）。
 *
 * <p>提供分页查询，可按状态过滤；支持单条 / 批量更改状态、删除。
 */
@RestController
@RequestMapping("/api/v1/admin/resonance-management")
public class AdminResonanceManagementController {

    private static final Logger log = LoggerFactory.getLogger(AdminResonanceManagementController.class);
    private static final Logger audit = LoggerFactory.getLogger("admin-audit");

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 500;

    private final ResonanceSpaceRepository resonanceRepository;

    public AdminResonanceManagementController(ResonanceSpaceRepository resonanceRepository) {
        this.resonanceRepository = resonanceRepository;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminResonanceRow(
            String id,
            String memoryAId,
            String memoryBId,
            Double resonanceScore,
            Double emotionSimilarity,
            Double sceneSimilarity,
            String status,
            String createdAt
    ) {}

    private static AdminResonanceRow toRow(ResonanceSpace r) {
        return new AdminResonanceRow(
                r.getId(),
                r.getMemoryId1(),
                r.getMemoryId2(),
                r.getSimilarityScore(),
                r.getEmotionSimilarity(),
                r.getSceneSimilarity(),
                r.getStatus(),
                r.getCreatedAt() == null ? null : r.getCreatedAt().toString()
        );
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<AdminResonanceRow>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "similarityScore") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            HttpServletRequest req) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Sort sort = "asc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        PageRequest pr = PageRequest.of(safePage, safeSize, sort);

        Page<ResonanceSpace> result;
        if (status != null && !status.isBlank()) {
            String s = status.trim();
            // 用 Specification-less 方案：直接 query
            org.springframework.data.jpa.domain.Specification<ResonanceSpace> spec =
                    (root, q, cb) -> cb.equal(root.get("status"), s);
            // 通过 findAll+ Specification 方式调用 — repository 没继承 JpaSpecificationExecutor，
            // 这里改用直接的 findAll(pageable) 后过滤；但这不高效。改成手动 JPQL 比较麻烦
            // —— 简化：分支两条路径
            List<ResonanceSpace> all = resonanceRepository.findAll(sort)
                    .stream()
                    .filter(r -> s.equals(r.getStatus()))
                    .toList();
            int from = Math.min(safePage * safeSize, all.size());
            int to = Math.min(from + safeSize, all.size());
            List<AdminResonanceRow> rows = all.subList(from, to).stream()
                    .map(AdminResonanceManagementController::toRow)
                    .toList();
            logAccess(req, "/api/v1/admin/resonance-management",
                    "list status=" + s + " page=" + safePage, 200);
            return ResponseEntity.ok(ApiResponse.success(
                    PageResult.of(rows, all.size(), safePage, safeSize)));
        }
        result = resonanceRepository.findAll(pr);
        List<AdminResonanceRow> rows = result.getContent().stream()
                .map(AdminResonanceManagementController::toRow)
                .toList();
        logAccess(req, "/api/v1/admin/resonance-management", "list", 200);
        return ResponseEntity.ok(ApiResponse.success(
                PageResult.of(rows, result.getTotalElements(), safePage, safeSize)));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<AdminResonanceRow>> patch(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        var opt = resonanceRepository.findById(id);
        if (opt.isEmpty()) throw new BizException(404, "RESONANCE_NOT_FOUND");
        ResonanceSpace r = opt.get();
        if (body.containsKey("status")) {
            String s = (String) body.get("status");
            if (s != null && !s.isBlank()) {
                String allowed = s.trim().toLowerCase();
                if (!allowed.equals("pending") && !allowed.equals("accepted")
                        && !allowed.equals("rejected") && !allowed.equals("archived")) {
                    throw new BizException(400, "INVALID_STATUS");
                }
                r.setStatus(allowed);
            }
        }
        resonanceRepository.save(r);
        logAccess(req, "/api/v1/admin/resonance-management/" + id, "patched", 200);
        return ResponseEntity.ok(ApiResponse.success(toRow(r)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteOne(@PathVariable String id, HttpServletRequest req) {
        if (!resonanceRepository.existsById(id)) {
            throw new BizException(404, "RESONANCE_NOT_FOUND");
        }
        resonanceRepository.deleteById(id);
        logAccess(req, "/api/v1/admin/resonance-management/" + id, "deleted", 200);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }

    @PostMapping("/batch-delete")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDelete(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BizException(400, "IDS_REQUIRED");
        if (ids.size() > MAX_BATCH_SIZE) throw new BizException(400, "BATCH_TOO_LARGE");
        int deleted = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            try {
                resonanceRepository.deleteById(id);
                deleted++;
            } catch (Exception e) {
                log.warn("[admin] batch-delete resonance failed for {}: {}", id, e.toString());
                failed.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("failed", failed);
        logAccess(req, "/api/v1/admin/resonance-management/batch-delete",
                "deleted=" + deleted, 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/batch-status")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchStatus(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        String status = (String) body.get("status");
        if (ids == null || ids.isEmpty()) throw new BizException(400, "IDS_REQUIRED");
        if (status == null || status.isBlank()) throw new BizException(400, "STATUS_REQUIRED");
        if (ids.size() > MAX_BATCH_SIZE) throw new BizException(400, "BATCH_TOO_LARGE");
        String s = status.trim().toLowerCase();
        if (!s.equals("pending") && !s.equals("accepted") && !s.equals("rejected") && !s.equals("archived")) {
            throw new BizException(400, "INVALID_STATUS");
        }
        int updated = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            var opt = resonanceRepository.findById(id);
            if (opt.isPresent()) {
                ResonanceSpace r = opt.get();
                r.setStatus(s);
                resonanceRepository.save(r);
                updated++;
            }
        }
        logAccess(req, "/api/v1/admin/resonance-management/batch-status",
                "updated=" + updated + " status=" + s, 200);
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated", updated, "status", s)));
    }

    private void logAccess(HttpServletRequest req, String path, String detail, int status) {
        try {
            String adminUserId = req.getHeader("X-User-Id");
            audit.info("admin-management adminUserId={} path={} detail={} status={}",
                    adminUserId, path, detail, status);
        } catch (Exception ignore) {}
    }
}
