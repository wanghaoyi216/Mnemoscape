package com.mnemoscape.ai.client;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

/**
 * Feign client to asset-service.
 *
 * <p>Used by {@code MinioMediaFetchTool} to materialize MinIO presigned
 * URLs into the AI's tool-call result. Two surface points are reused:
 *
 * <ul>
 *   <li>{@code /api/v1/assets/static/resources} — already returns the
 *       merged list of local + MinIO objects with {@code path} pre-filled
 *       (presigned URLs for {@code kind=minio}).</li>
 *   <li>{@code /api/v1/assets/{objectName}/url} — direct presigned-URL
 *       lookup by object name; used when memory rows reference a
 *       specific MinIO {@code objectKey}.</li>
 * </ul>
 *
 * <p>The Feign client is intentionally lenient: if asset-service is down,
 * the tool returns an empty media list with {@code degraded=true} rather
 * than failing the whole chat turn.
 */
@FeignClient(name = "asset-service", contextId = "assetService", path = "/api/v1/assets")
public interface AssetServiceClient {

    @GetMapping("/static/resources")
    ApiResponse<List<Map<String, Object>>> listStaticResources();

    @GetMapping("/{objectName}/url")
    ApiResponse<Map<String, String>> presignedUrl(@PathVariable("objectName") String objectName);
}
