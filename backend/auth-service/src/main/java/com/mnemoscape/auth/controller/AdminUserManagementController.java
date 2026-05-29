package com.mnemoscape.auth.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.dto.PageResult;
import com.mnemoscape.common.exception.BizException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 — 用户管理（数据表格 + 批量操作）。
 *
 * <p>功能：
 * <ul>
 *   <li>分页 / 搜索 / 过滤角色查询</li>
 *   <li>更改单个用户角色（USER ↔ ADMIN）</li>
 *   <li>验证 / 禁用单个用户（标记 verified=false 视为冻结）</li>
 *   <li>删除用户（hard delete，慎用）</li>
 *   <li>批量删除 / 批量更改角色</li>
 * </ul>
 *
 * <p>响应严格白名单 —— 不返回 passwordHash；email 作为管理员身份核验需要展示但仅给 ADMIN 看。
 */
@RestController
@RequestMapping("/api/v1/admin/users-management")
public class AdminUserManagementController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserManagementController.class);
    private static final Logger audit = LoggerFactory.getLogger("admin-audit");

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 200;

    private final UserRepository userRepository;

    public AdminUserManagementController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUserRow(
            String id,
            String username,
            String email,
            String avatarUrl,
            String role,
            Boolean verified,
            String createdAt,
            String updatedAt
    ) {}

    private static AdminUserRow toRow(User u) {
        return new AdminUserRow(
                u.getId(),
                u.getUsername(),
                u.getEmail(),
                u.getAvatarUrl(),
                u.getRole(),
                u.getVerified(),
                u.getCreatedAt() == null ? null : u.getCreatedAt().toString(),
                u.getUpdatedAt() == null ? null : u.getUpdatedAt().toString()
        );
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<AdminUserRow>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            HttpServletRequest req) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Sort sort = "asc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();

        Specification<User> spec = (root, q, cb) -> cb.conjunction();
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)
            ));
        }
        if (role != null && !role.isBlank()) {
            String r = role.trim().toUpperCase();
            if (!r.equals("USER") && !r.equals("ADMIN")) {
                throw new BizException(400, "INVALID_ROLE_FILTER");
            }
            spec = spec.and((root, q, cb) -> cb.equal(root.get("role"), r));
        }
        if (verified != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("verified"), verified));
        }

        Page<User> result = userRepository.findAll(spec, PageRequest.of(safePage, safeSize, sort));
        List<AdminUserRow> rows = result.getContent().stream()
                .map(AdminUserManagementController::toRow)
                .toList();
        logAccess(req, "/api/v1/admin/users-management", "list", 200);
        return ResponseEntity.ok(ApiResponse.success(
                PageResult.of(rows, result.getTotalElements(), safePage, safeSize)));
    }

    /** 修改单用户角色。body: {role: "USER"|"ADMIN"} */
    @PatchMapping("/{userId}/role")
    @Transactional
    public ResponseEntity<ApiResponse<AdminUserRow>> changeRole(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String role = (String) body.get("role");
        if (role == null || role.isBlank()) {
            throw new BizException(400, "ROLE_REQUIRED");
        }
        String r = role.trim().toUpperCase();
        if (!r.equals("USER") && !r.equals("ADMIN")) {
            throw new BizException(400, "INVALID_ROLE");
        }
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "USER_NOT_FOUND"));
        // 防止把最后一个 ADMIN 降级
        if (r.equals("USER") && "ADMIN".equals(u.getRole())) {
            long adminCount = userRepository.findAll(
                    (root, q, cb) -> cb.equal(root.get("role"), "ADMIN")
            ).size();
            if (adminCount <= 1) {
                throw new BizException(400, "CANNOT_DEMOTE_LAST_ADMIN");
            }
        }
        u.setRole(r);
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
        logAccess(req, "/api/v1/admin/users-management/" + userId + "/role", "role=" + r, 200);
        return ResponseEntity.ok(ApiResponse.success(toRow(u)));
    }

    /** 修改 verified 状态（管理员可手动验证 / 冻结用户）。body: {verified: true|false} */
    @PatchMapping("/{userId}/verified")
    @Transactional
    public ResponseEntity<ApiResponse<AdminUserRow>> changeVerified(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Object v = body.get("verified");
        if (!(v instanceof Boolean b)) {
            throw new BizException(400, "VERIFIED_FLAG_REQUIRED");
        }
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "USER_NOT_FOUND"));
        u.setVerified(b);
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
        logAccess(req, "/api/v1/admin/users-management/" + userId + "/verified",
                "verified=" + b, 200);
        return ResponseEntity.ok(ApiResponse.success(toRow(u)));
    }

    /** 删除单个用户（hard delete）。需要管理员明确确认。 */
    @DeleteMapping("/{userId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteOne(
            @PathVariable String userId,
            HttpServletRequest req) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "USER_NOT_FOUND"));
        // 防止删除最后一个 ADMIN
        if ("ADMIN".equals(u.getRole())) {
            long adminCount = userRepository.findAll(
                    (root, q, cb) -> cb.equal(root.get("role"), "ADMIN")
            ).size();
            if (adminCount <= 1) {
                throw new BizException(400, "CANNOT_DELETE_LAST_ADMIN");
            }
        }
        // 防止管理员删除自己
        String callerId = req.getHeader("X-User-Id");
        if (callerId != null && callerId.equals(userId)) {
            throw new BizException(400, "CANNOT_DELETE_SELF");
        }
        userRepository.delete(u);
        logAccess(req, "/api/v1/admin/users-management/" + userId, "deleted", 200);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }

    /** 批量删除。body: {ids: [...]} */
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
        String callerId = req.getHeader("X-User-Id");
        long adminCount = userRepository.findAll(
                (root, q, cb) -> cb.equal(root.get("role"), "ADMIN")
        ).size();

        int deleted = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || id.equals(callerId)) {
                failed.add(id);
                continue;
            }
            try {
                var opt = userRepository.findById(id);
                if (opt.isPresent()) {
                    User u = opt.get();
                    if ("ADMIN".equals(u.getRole()) && adminCount <= 1) {
                        failed.add(id);
                        continue;
                    }
                    if ("ADMIN".equals(u.getRole())) adminCount--;
                    userRepository.delete(u);
                    deleted++;
                } else {
                    failed.add(id);
                }
            } catch (Exception e) {
                log.warn("[admin] batch-delete user failed for {}: {}", id, e.toString());
                failed.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("failed", failed);
        logAccess(req, "/api/v1/admin/users-management/batch-delete",
                "deleted=" + deleted, 200);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 单条详情（包含 createdAt 等管理员用得到的字段）。 */
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<AdminUserRow>> getOne(
            @PathVariable String userId,
            HttpServletRequest req) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "USER_NOT_FOUND"));
        logAccess(req, "/api/v1/admin/users-management/" + userId, "get", 200);
        return ResponseEntity.ok(ApiResponse.success(toRow(u)));
    }

    private void logAccess(HttpServletRequest req, String path, String detail, int status) {
        try {
            String adminUserId = req.getHeader("X-User-Id");
            audit.info("admin-management adminUserId={} path={} detail={} status={}",
                    adminUserId, path, detail, status);
        } catch (Exception ignore) {
            // never break the response path
        }
    }
}
