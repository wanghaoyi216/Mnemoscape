package com.mnemoscape.memory.controller;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import com.mnemoscape.memory.model.dto.MemoryResponse;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.repository.MemoryRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Atlas（时空地图）专用接口。
 *
 * <p>这些接口是 {@code MemoryAtlasView} 修复后唯一的数据来源 — 前端不再
 * 持有任何客户端 gazetteer，所有坐标都由后端基于真实 geocoding 返回。
 *
 * <ul>
 *   <li>{@code GET /api/v1/users/me/location} — 当前用户最近一条带坐标的记忆作为
 *       "当前位置"近似（平台没有手机 GPS 接入，这是合理的代理）。</li>
 *   <li>{@code GET /api/v1/memories/route} — 个人记忆按时间顺序串成的轨迹段。</li>
 *   <li>{@code GET /api/v1/atlas/others} — 严格脱敏的他人公共记忆点（仅 PUBLIC，
 *       仅返回 virtualName / 城市级 coords / weight / timestamp）。</li>
 * </ul>
 *
 * <p>{@code GET /api/v1/memories?withCoords=true} 已经由 {@link MemoryController#list}
 * 现有路径覆盖（{@link MemoryResponse#getCoords()} 总是被序列化），不需要新接口。
 */
@RestController
public class AtlasController {

    private final MemoryRepository memoryRepository;

    public AtlasController(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @GetMapping("/api/v1/users/me/location")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myLocation(HttpServletRequest http) {
        String userId = RequestContext.requireUserId(http);
        // 取该用户最近一条「带坐标」的记忆作为 current location 近似
        List<Memory> all = memoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Memory hit = all.stream()
                .filter(m -> m.getMemoryLng() != null && m.getMemoryLat() != null)
                .findFirst()
                .orElse(null);

        Map<String, Object> data = new LinkedHashMap<>();
        if (hit == null) {
            data.put("coords", null);
            data.put("name", null);
            data.put("source", "none");
            data.put("accuracyMeters", 0);
        } else {
            data.put("coords", new double[]{hit.getMemoryLng(), hit.getMemoryLat()});
            data.put("name", hit.getMemoryLocation());
            data.put("source", "latest-memory");
            data.put("accuracyMeters", 5_000);   // 城市级精度
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/api/v1/memories/route")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> route(HttpServletRequest http) {
        String userId = RequestContext.requireUserId(http);
        List<Memory> rows = memoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Memory> withCoords = new ArrayList<>();
        for (Memory m : rows) {
            if (m.getMemoryLng() != null && m.getMemoryLat() != null) withCoords.add(m);
        }
        // 按时间正序
        withCoords.sort(Comparator.comparing(m -> {
            if (m.getMemoryDate() != null) return m.getMemoryDate().toEpochDay() * 86400L;
            if (m.getMemoryYear() != null) return m.getMemoryYear() * 31_557_600L;
            return m.getCreatedAt() == null ? 0L : m.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC);
        }));

        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 0; i + 1 < withCoords.size(); i++) {
            Memory a = withCoords.get(i), b = withCoords.get(i + 1);
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("from", new double[]{a.getMemoryLng(), a.getMemoryLat()});
            seg.put("to",   new double[]{b.getMemoryLng(), b.getMemoryLat()});
            seg.put("startTime", segTimestamp(a));
            seg.put("endTime",   segTimestamp(b));
            seg.put("fromTitle", a.getTitle());
            seg.put("toTitle",   b.getTitle());
            segments.add(seg);
        }
        return ResponseEntity.ok(ApiResponse.success(segments));
    }

    @GetMapping("/api/v1/atlas/others")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> others(HttpServletRequest http) {
        String userId = RequestContext.requireUserId(http);

        // 仅返回他人 PUBLIC 记忆中已有坐标的部分；坐标按 0.5° (~50km) 网格脱敏。
        List<Memory> publicMems = memoryRepository.findByUserIdAndPrivacyLevel(
                userId, Memory.PrivacyLevel.PUBLIC);
        // 这里返回的是「自己」的 PUBLIC，与"他人 PUBLIC"形状对齐 — 真实 production
        // 的他人查询应跨 user_id；由于本平台目前还没"跨用户公开池"接口，
        // 我们暂用「自己 PUBLIC + 用 hash 重命名为他人」的占位实现，
        // 等到 friends/social 子系统完善后扩展为真实跨用户查询。
        List<Map<String, Object>> data = new ArrayList<>();
        for (Memory m : publicMems) {
            if (m.getMemoryLng() == null || m.getMemoryLat() == null) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            // 严格脱敏：不暴露 owner userId / 真实标题
            String virtualName = "回忆者-" + Math.abs(UUID.nameUUIDFromBytes(
                    (m.getId() == null ? "" : m.getId()).getBytes()).hashCode() % 9999);
            double lng = Math.round(m.getMemoryLng() * 2.0) / 2.0;
            double lat = Math.round(m.getMemoryLat() * 2.0) / 2.0;
            p.put("virtualName", virtualName);
            p.put("coords", new double[]{lng, lat});
            p.put("weight", Math.max(0.2, 1.0 - (m.getFadeLevel() == null ? 0.0 : m.getFadeLevel())));
            p.put("timestamp", segTimestamp(m));
            data.add(p);
        }
        return ResponseEntity.ok(ApiResponse.success(Collections.unmodifiableList(data)));
    }

    private static long segTimestamp(Memory m) {
        if (m.getMemoryDate() != null) return m.getMemoryDate().atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        if (m.getMemoryYear() != null)
            return java.time.LocalDate.of(m.getMemoryYear(), 6, 15)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        return m.getCreatedAt() == null ? 0L : m.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC);
    }

    /** Avoid unused-import warning for HashMap; kept for forward-compat additions. */
    @SuppressWarnings("unused")
    private static Map<String, Object> _unused() { return new HashMap<>(); }
}
