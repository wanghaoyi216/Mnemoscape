package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.config.VectorStoreProperties;
import com.mnemoscape.ai.model.dto.VectorStatusResponse;
import com.mnemoscape.ai.service.MilvusVectorStore;
import com.mnemoscape.ai.service.VectorIndexService;
import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
    public ResponseEntity<ApiResponse<VectorStatusResponse>> status() {
        Map<String, Object> storeStatus = vectorStore.status();

        VectorStatusResponse.MilvusStatus milvus = VectorStatusResponse.MilvusStatus.builder()
                .collection((String) storeStatus.getOrDefault("collection", props.getCollectionName()))
                .ready(Boolean.TRUE.equals(storeStatus.get("collectionReady")))
                .indexType((String) storeStatus.getOrDefault("indexType", "UNKNOWN"))
                .metricType((String) storeStatus.getOrDefault("metricType", "UNKNOWN"))
                // 真实 entityCount / userCount（来自 Milvus query，60s TTL 缓存）。
                // 截断时 entityCount 读为 "≥N"，UI 可显示 "≥3,200" 之类。
                .entityCount(((Number) storeStatus.getOrDefault("entityCount", 0L)).longValue())
                .userCount(((Number) storeStatus.getOrDefault("userCount", 0L)).longValue())
                .entityCountTruncated(Boolean.TRUE.equals(storeStatus.get("entityCountTruncated")))
                .entityCountAsOf(formatEpoch((Long) storeStatus.getOrDefault("entityCountAsOf", 0L)))
                .lastUpsertOkAt(formatEpoch((Long) storeStatus.getOrDefault("lastUpsertOkAt", 0L)))
                .lastUpsertFailAt(formatEpoch((Long) storeStatus.getOrDefault("lastUpsertFailAt", 0L)))
                .lastError((String) storeStatus.get("lastError"))
                .build();

        VectorStatusResponse resp = VectorStatusResponse.builder()
                .enabled(props.isEnabled())
                .available(indexService.isReady())
                .model(props.getEmbeddingModel())
                .dim(props.getEmbeddingDimension())
                .observedDim(indexService.getLastObservedDimension())
                .milvus(milvus)
                .build();

        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    /** epoch ms → ISO-8601 UTC；0L / null 视为"未发生" → 返回 null。 */
    private static String formatEpoch(Long epochMs) {
        if (epochMs == null || epochMs <= 0L) return null;
        return Instant.ofEpochMilli(epochMs).toString();
    }
}
