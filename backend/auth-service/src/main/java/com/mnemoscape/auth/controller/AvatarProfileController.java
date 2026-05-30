package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.model.dto.AvatarProfileRequest;
import com.mnemoscape.auth.model.dto.AvatarProfileResponse;
import com.mnemoscape.auth.service.AvatarProfileService;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 用户 3D 刻画档案 API。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET  /api/v1/users/me/avatar-profile} — 获取当前用户的 3D 刻画档案</li>
 *   <li>{@code POST /api/v1/users/me/avatar-profile} — 创建或更新 3D 刻画档案（AI 生成）</li>
 *   <li>{@code DELETE /api/v1/users/me/avatar-profile} — 删除 3D 刻画档案</li>
 *   <li>{@code GET  /api/v1/users/{userId}/avatar-profile} — 获取指定用户的公开 3D 刻画档案</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class AvatarProfileController {

    private final AvatarProfileService avatarProfileService;

    public AvatarProfileController(AvatarProfileService avatarProfileService) {
        this.avatarProfileService = avatarProfileService;
    }

    /**
     * 获取当前用户的 3D 刻画档案。
     * 若尚未创建，返回 data=null（前端据此判断是否显示"创建"引导）。
     */
    @GetMapping("/users/me/avatar-profile")
    public ResponseEntity<ApiResponse<AvatarProfileResponse>> getMyAvatarProfile(
            HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        AvatarProfileResponse profile = avatarProfileService.getByUserId(userId);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    /**
     * 创建或更新当前用户的 3D 刻画档案。
     * 调用 AI 服务根据自我描述生成角色特征（约 5-15 秒）。
     */
    @PostMapping("/users/me/avatar-profile")
    public ResponseEntity<ApiResponse<AvatarProfileResponse>> createOrUpdateAvatarProfile(
            @Valid @RequestBody AvatarProfileRequest body,
            HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        AvatarProfileResponse profile = avatarProfileService.createOrUpdate(userId, body);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    /**
     * 删除当前用户的 3D 刻画档案。
     */
    @DeleteMapping("/users/me/avatar-profile")
    public ResponseEntity<ApiResponse<Void>> deleteMyAvatarProfile(
            HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        avatarProfileService.deleteByUserId(userId);
        return ResponseEntity.ok(ApiResponse.success("Avatar profile deleted", null));
    }

    /**
     * 获取指定用户的公开 3D 刻画档案（用于共鸣空间展示）。
     * 若档案不存在或未公开，返回 data=null。
     */
    @GetMapping("/users/{userId}/avatar-profile")
    public ResponseEntity<ApiResponse<AvatarProfileResponse>> getUserAvatarProfile(
            @PathVariable String userId) {
        AvatarProfileResponse profile = avatarProfileService.getByUserId(userId);
        // 非公开档案对外不可见
        if (profile != null && !Boolean.TRUE.equals(profile.getIsPublic())) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        return ResponseEntity.ok(ApiResponse.success(profile));
    }
}
