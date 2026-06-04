package com.mnemoscape.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.auth.client.MemoryServiceClient;
import com.mnemoscape.auth.model.dto.AvatarProfileRequest;
import com.mnemoscape.auth.model.dto.AvatarProfileResponse;
import com.mnemoscape.auth.service.AvatarProfileService;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 用户 3D 刻画档案 API。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET  /api/v1/users/me/avatar-profile} — 获取当前用户的 3D 刻画档案</li>
 *   <li>{@code POST /api/v1/users/me/avatar-profile} — 创建或更新 3D 刻画档案（AI 生成）</li>
 *   <li>{@code DELETE /api/v1/users/me/avatar-profile} — 删除 3D 刻画档案</li>
 *   <li>{@code GET  /api/v1/users/{userId}/avatar-profile} — 获取指定用户的公开 3D 刻画档案</li>
 *   <li>{@code GET  /api/v1/users/me/emotion-summary} — 情绪画像聚合摘要（D3 雷达图）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class AvatarProfileController {

    private static final Logger log = LoggerFactory.getLogger(AvatarProfileController.class);

    /**
     * 雷达图固定维度（与 emotionProfile JSON 的 5 个 key 完全对齐）：
     * joy / sorrow / fear / calm / nostalgia
     * 任何未识别维度都汇总到 "other"，保证雷达图形状稳定。
     */
    private static final List<String> FIXED_DIMS = List.of("joy", "sorrow", "fear", "calm", "nostalgia");
    private static final int MAX_MEMORIES_PER_USER = 50;
    private static final int WINDOW_DAYS = 30;

    private final AvatarProfileService avatarProfileService;
    private final MemoryServiceClient memoryServiceClient;
    private final ObjectMapper objectMapper;

    public AvatarProfileController(AvatarProfileService avatarProfileService,
                                   MemoryServiceClient memoryServiceClient,
                                   ObjectMapper objectMapper) {
        this.avatarProfileService = avatarProfileService;
        this.memoryServiceClient = memoryServiceClient;
        this.objectMapper = objectMapper;
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

    /**
     * 情绪画像聚合摘要 — 用于前端 D3 雷达图。
     *
     * <p>真实实现链路（v2）：
     * <ol>
     *   <li>auth-service 调 memory-service {@code GET /api/v1/memories} 拉取
     *       当前用户最近 50 条记忆（不传 privacyLevel，保留 PRIVATE/FRIENDS/PUBLIC 全量，
     *       雷达图反映用户的真实情绪面）。</li>
     *   <li>对每条记忆的 {@code emotionProfile} 字段（JSON 字符串）做反序列化，提取
     *       joy / sorrow / fear / calm / nostalgia 五维分数；未识别的 key 汇总到 other。</li>
     *   <li>用「按记忆数」求算术平均；空集时返回全 0。</li>
     *   <li>返回 {@code { enabled, profile, sampleSize, windowDays, generatedAt }}。</li>
     * </ol>
     *
     * <p>失败策略：调用方未认证 / memory-service 不可达 / 任何异常 → 返回
     * {@code { enabled: false, message: "..." }} 让前端显示"暂不可用"占位，
     * 不抛 5xx 污染调用方页面。
     */
    @GetMapping("/users/me/emotion-summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyEmotionSummary(
            HttpServletRequest request) {
        // 仍走 @PreAuthorize / RequestContext 解析过的 JWT；Feign 全局
        // FeignAuthForwardingInterceptor 会自动把 Authorization 透传下去。
        String userId;
        try {
            userId = RequestContext.requireUserId(request);
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(buildDisabled("unauthenticated")));
        }
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(buildDisabled("unauthenticated")));
        }

        try {
            ApiResponse<Map<String, Object>> resp = memoryServiceClient.listMemoriesForEmotion(
                    0, MAX_MEMORIES_PER_USER);
            Map<String, Object> body = resp == null ? null : resp.getData();
            if (body == null) {
                return ResponseEntity.ok(ApiResponse.success(buildDisabled("upstream returned null")));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
            if (items == null || items.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(buildEmpty()));
            }

            Map<String, Double> sum = new LinkedHashMap<>();
            for (String d : FIXED_DIMS) sum.put(d, 0.0);
            sum.put("other", 0.0);
            int counted = 0;

            for (Map<String, Object> item : items) {
                Object epRaw = item.get("emotionProfile");
                if (epRaw == null) continue;
                String ep = epRaw.toString();
                if (ep.isBlank()) continue;
                JsonNode node;
                try {
                    node = objectMapper.readTree(ep);
                } catch (Exception parseErr) {
                    // 单条脏数据跳过，不影响整体聚合
                    log.debug("emotion-summary: skip unparsable profile, len={}", ep.length());
                    continue;
                }
                if (!node.isObject()) continue;
                double total = 0.0;
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    JsonNode v = e.getValue();
                    if (v == null || !v.isNumber()) continue;
                    String k = e.getKey().toLowerCase(Locale.ROOT);
                    double score = v.asDouble(0.0);
                    if (FIXED_DIMS.contains(k)) {
                        sum.merge(k, score, Double::sum);
                    } else {
                        sum.merge("other", score, Double::sum);
                    }
                    total += Math.abs(score);
                }
                // total 校验：emotionProfile 应当是「5 维分数向量」，若全 0/全空视为这条数据无效
                if (total > 0.0001) counted++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", true);
            Map<String, Double> avg = new LinkedHashMap<>();
            for (String d : FIXED_DIMS) {
                avg.put(d, counted == 0 ? 0.0 : round(sum.get(d) / counted));
            }
            avg.put("other", counted == 0 ? 0.0 : round(sum.get("other") / counted));
            result.put("profile", avg);
            result.put("sampleSize", counted);
            result.put("windowDays", WINDOW_DAYS);
            result.put("generatedAt", System.currentTimeMillis());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("emotion-summary: upstream call failed, returning disabled: {}", e.toString());
            return ResponseEntity.ok(ApiResponse.success(buildDisabled("upstream unavailable")));
        }
    }

    private static Map<String, Object> buildDisabled(String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", false);
        data.put("message", "情绪画像暂不可用");
        data.put("reason", reason);
        return data;
    }

    private static Map<String, Object> buildEmpty() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", true);
        Map<String, Double> zero = new LinkedHashMap<>();
        for (String d : FIXED_DIMS) zero.put(d, 0.0);
        zero.put("other", 0.0);
        data.put("profile", zero);
        data.put("sampleSize", 0);
        data.put("windowDays", WINDOW_DAYS);
        data.put("generatedAt", System.currentTimeMillis());
        return data;
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 1000.0) / 1000.0;
    }
}
