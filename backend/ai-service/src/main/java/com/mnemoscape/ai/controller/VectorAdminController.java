package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.config.VectorStoreProperties;
import com.mnemoscape.ai.service.MilvusVectorStore;
import com.mnemoscape.ai.service.VectorIndexService;
import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向量库自检端点 —— admin 排障 / 监控用（对应任务 B4）。
 *
 * <p>路径：{@code GET /api/v1/admin/vector/status}。在网关层会有
 * {@code AdminGuardFilter} 检查 role=admin；本服务不重复鉴权（直连调用的鉴权
 * 由 {@code SecurityConfig} 的 {@code /api/v1/admin/**} 链兜底）。
 *
 *   <p>返回结构：
 * <pre>
 * {
 *   "enabled":    true,
 *   "available":  true,
 *   "model":      "nvidia/nv-embed-v1",
 *   "dim":        4096,
 *   "observedDim": 4096,        // -1 = 还没真实 embed 过
 *   "milvus": {
 *     "collection":      "mnemoscape_memories",
 *     "ready":           true,
 *     "indexType":       "HNSW",
 *     "metricType":      "COSINE",
 *     "entityCount":     42,         // 真实值（60s TTL 缓存）
 *     "userCount":       7,          // 真实值
 *     "entityCountTruncated": false, // true=扫描被 1w 上限截断，UI 应展示 "≥N"
 *     "entityCountAsOf": "2026-06-04T21:35:00Z",
 *     "lastUpsertOkAt":  "2026-06-03T10:00:00Z" | null,
 *     "lastUpsertFailAt": null,
 *     "lastError":       null
 *   }
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/vector")
public class VectorAdminController {

    private final VectorStoreProperties props;
    private final VectorIndexService indexService;
    private final MilvusVectorStore vectorStore;

    public VectorAdminController(VectorStoreProperties props,
                                 VectorIndexService indexService,
                                 MilvusVectorStore vectorStore) {
        this.props = props;
        this.indexService = indexService;
        this.vectorStore = vectorStore;
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("enabled", props.isEnabled());
        root.put("available", indexService.isReady());
        root.put("model", props.getEmbeddingModel());
        root.put("dim", props.getEmbeddingDimension());
        root.put("observedDim", indexService.getLastObservedDimension());

        Map<String, Object> milvus = new LinkedHashMap<>();
        Map<String, Object> storeStatus = vectorStore.status();
        milvus.put("collection", storeStatus.getOrDefault("collection", props.getCollectionName()));
        milvus.put("ready", Boolean.TRUE.equals(storeStatus.get("collectionReady")));
        milvus.put("indexType", storeStatus.getOrDefault("indexType", "UNKNOWN"));
        milvus.put("metricType", storeStatus.getOrDefault("metricType", "UNKNOWN"));
        // 真实 entityCount / userCount（来自 Milvus query，60s TTL 缓存）。
        // 截断时 entityCount 读为 "≥N"，UI 可显示 "≥3,200" 之类。
        milvus.put("entityCount", storeStatus.getOrDefault("entityCount", 0L));
        milvus.put("userCount", storeStatus.getOrDefault("userCount", 0L));
        milvus.put("entityCountTruncated", storeStatus.getOrDefault("entityCountTruncated", false));
        milvus.put("entityCountAsOf", formatEpoch((Long) storeStatus.getOrDefault("entityCountAsOf", 0L)));
        milvus.put("lastUpsertOkAt", formatEpoch((Long) storeStatus.getOrDefault("lastUpsertOkAt", 0L)));
        milvus.put("lastUpsertFailAt", formatEpoch((Long) storeStatus.getOrDefault("lastUpsertFailAt", 0L)));
        milvus.put("lastError", storeStatus.get("lastError"));
        root.put("milvus", milvus);

        return ResponseEntity.ok(ApiResponse.success(root));
    }

    /** epoch ms → ISO-8601 UTC；0L / null 视为"未发生" → 返回 null。 */
    private static String formatEpoch(Long epochMs) {
        if (epochMs == null || epochMs <= 0L) return null;
        return Instant.ofEpochMilli(epochMs).toString();
    }
}
