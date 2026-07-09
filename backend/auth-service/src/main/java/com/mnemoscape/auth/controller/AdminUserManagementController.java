package com.mnemoscape.auth.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.service.AdminUserManagementService;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.dto.PageResult;
import com.mnemoscape.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users-management")
public class AdminUserManagementController {

    private static final Logger audit = LoggerFactory.getLogger("admin-audit");

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 200;

    private final AdminUserManagementService adminService;

    public AdminUserManagementController(AdminUserManagementService adminService) {
        this.adminService = adminService;
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

        Page<User> result = adminService.listUsers(safePage, safeSize, search, role, verified, sortBy, sortDir);
        List<AdminUserRow> rows = result.getContent().stream()
                .map(AdminUserManagementController::toRow)
                .toList();
        logAccess(req, "/api/v1/admin/users-management", "list", 200);
        return ResponseEntity.ok(ApiResponse.success(
                PageResult.of(rows, result.getTotalElements(), safePage, safeSize)));
    }

    /** 修改单用户角色。body: {role: "USER"|"ADMIN"} */
    @PatchMapping("/{userId}/role")
    public ResponseEntity<ApiResponse<AdminUserRow>> changeRole(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String role = (String) body.get("role");
        if (role == null || role.isBlank()) {
            throw new BizException(400, "ROLE_REQUIRED");
        }
        User u = adminService.changeRole(userId, role);
        logAccess(req, "/api/v1/admin/users-management/" + userId + "/role",
                "role=" + u.getRole(), 200);
        return ResponseEntity.ok(ApiResponse.success(toRow(u)));
    }

    /** 修改 verified 状态（管理员可手动验证 / 冻结用户）。body: {verified: true|false} */
    @PatchMapping("/{userId}/verified")
    public ResponseEntity<ApiResponse<AdminUserRow>> changeVerified(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Object v = body.get("verified");
        if (!(v instanceof Boolean b)) {
            throw new BizException(400, "VERIFIED_FLAG_REQUIRED");
        }
        User u = adminService.changeVerified(userId, b);
        logAccess(req, "/api/v1/admin/users-management/" + userId + "/verified",
                "verified=" + b, 200);
        return ResponseEntity.ok(ApiResponse.success(toRow(u)));
    }

    /** 删除单个用户（hard delete）。需要管理员明确确认。 */
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> deleteOne(
            @PathVariable String userId,
            HttpServletRequest req) {
        String callerId = req.getHeader("X-User-Id");
        if (callerId == null || callerId.isBlank()) {
            throw BizException.unauthorized();
        }
        adminService.deleteUser(userId, callerId);
        logAccess(req, "/api/v1/admin/users-management/" + userId, "deleted", 200);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }

    /** 批量删除。body: {ids: [...]} */
    @PostMapping("/batch-delete")
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
        if (callerId == null || callerId.isBlank()) {
            throw BizException.unauthorized();
        }
        AdminUserManagementService.BatchDeleteResult result = adminService.batchDelete(ids, callerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", result.deleted());
        out.put("failed", result.failed());
        logAccess(req, "/api/v1/admin/users-management/batch-delete",
                "deleted=" + result.deleted(), 200);
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    /** 单条详情（包含 createdAt 等管理员用得到的字段）。 */
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<AdminUserRow>> getOne(
            @PathVariable String userId,
            HttpServletRequest req) {
        User u = adminService.getUser(userId);
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
