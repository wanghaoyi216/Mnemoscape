package com.mnemoscape.asset.controller;

import com.mnemoscape.asset.service.AssetService;
import com.mnemoscape.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理后台 — MinIO 历史资源迁移工具。
 *
 * <p>仅 ROLE_ADMIN 可访问（由 SecurityConfig 的 {@code /api/v1/admin/**} 链校验）。
 * 把早期无 {@code users/} 前缀的孤儿上传对象搬到 {@code legacy-orphan/}，消除
 * "他人私有上传被当成公共素材暴露"的隐私面。
 *
 * <p>默认 <b>dry-run</b>：先让管理员预览候选数量与样本，确认无误后再带
 * {@code apply=true} 真正迁移。
 */
@RestController
@RequestMapping("/api/v1/admin/assets")
public class AdminAssetController {

    private static final Logger log = LoggerFactory.getLogger(AdminAssetController.class);
    private static final Logger audit = LoggerFactory.getLogger("admin-audit");

    private final AssetService assetService;

    public AdminAssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    /**
     * 扫描 / 迁移历史孤儿对象。
     *
     * @param apply false（默认）= dry-run 仅预览；true = 实际移动
     */
    @PostMapping("/migrate-legacy-orphans")
    public ResponseEntity<ApiResponse<Map<String, Object>>> migrateLegacyOrphans(
            @RequestParam(value = "apply", defaultValue = "false") boolean apply,
            HttpServletRequest request) {
        Map<String, Object> result = assetService.migrateLegacyOrphans(!apply);
        try {
            audit.info("admin-asset-migrate adminUserId={} apply={} candidates={} migrated={}",
                    request.getHeader("X-User-Id"), apply,
                    result.get("candidates"), result.get("migrated"));
        } catch (Exception ignore) { /* never break response */ }
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
