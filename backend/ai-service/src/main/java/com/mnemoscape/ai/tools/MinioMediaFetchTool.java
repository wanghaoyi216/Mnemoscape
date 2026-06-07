package com.mnemoscape.ai.tools;

import com.mnemoscape.ai.client.AssetServiceClient;
import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 真实 MinIO 多媒体获取工具。
 *
 * <p>调用 asset-service：
 * <ol>
 *   <li>{@code GET /api/v1/assets/static/resources} 拿到合并后的本地 + MinIO 列表，
 *       筛选出 {@code kind=video|photo|gif|audio} 的条目；</li>
 *   <li>对存在的 MinIO 对象保留 presigned URL（asset-service 已经帮我们生成，
 *       URL 含 {@code X-Amz-Signature}），其他来源（本地静态 fallback）也保留
 *       但标 {@code source="local"}，让前端能区分。</li>
 * </ol>
 *
 * <p>asset-service 不可达 → 返回空列表 + {@code degraded=true}，前端自然降级到
 * 不显示卡片即可（{@code media-catalog} 不再被业务层使用，仅装饰性留存）。
 */
@Configuration
public class MinioMediaFetchTool {

    private static final Logger log = LoggerFactory.getLogger(MinioMediaFetchTool.class);

    public static class Request {
        /** 关键词过滤；为空则按 type 抓全部 */
        public String query;
        /** 想要的种类：video / photo / gif / audio / all。为空 = all */
        public String kind;
        /** 最多返回多少条；默认 4，最多 10 */
        public Integer limit;
    }

    public static class Media {
        public String url;
        public String mime;
        public String kind;
        public String name;
        public String source; // "minio" | "local"
        public Long size;

        public Media() {}
        public Media(String url, String mime, String kind, String name, String source, Long size) {
            this.url = url; this.mime = mime; this.kind = kind;
            this.name = name; this.source = source; this.size = size;
        }
    }

    public static class Response {
        public List<Media> media = new ArrayList<>();
        public boolean degraded = false;
        public String message;
    }

    private final AssetServiceClient client;

    public MinioMediaFetchTool(AssetServiceClient client) {
        this.client = client;
    }

    @Bean
    public FunctionCallback minioMediaFetchToolCallback() {
        Function<Request, Response> fn = this::fetch;
        return FunctionCallback.builder()
                .description("Fetch real multimodal cards (image/audio/video) from MinIO via "
                        + "asset-service. Returns presigned URLs that are safe to embed in "
                        + "the chat reply's `attachments`. Use when the user asks for "
                        + "images / audio / video / 'play' / 'show me' / 'multimodal'.")
                .function("minioMediaFetchTool", fn)
                .inputType(Request.class)
                .build();
    }

    public Response fetch(Request req) {
        Response resp = new Response();
        int limit = req == null || req.limit == null ? 4 : Math.max(1, Math.min(10, req.limit));
        String kind = req == null || req.kind == null ? "all" : req.kind.toLowerCase();
        String q = req == null || req.query == null ? "" : req.query.toLowerCase();

        try {
            ApiResponse<List<Map<String, Object>>> raw = client.listStaticResources();
            if (raw == null || raw.getData() == null) {
                resp.degraded = true;
                resp.message = "asset-service returned empty";
                return resp;
            }
            for (Map<String, Object> r : raw.getData()) {
                if (resp.media.size() >= limit) break;
                String type = String.valueOf(r.getOrDefault("type", ""));
                if (!"all".equals(kind) && !kind.equals(type)) continue;
                String name = String.valueOf(r.getOrDefault("name", ""));
                if (!q.isEmpty() && !name.toLowerCase().contains(q)) continue;

                String path = String.valueOf(r.getOrDefault("path", ""));
                if (path.isEmpty()) continue;
                // asset-service 把 MinIO presigned URL 直接放在 path（含 X-Amz-Signature 等查询参数）。
                boolean isMinio = path.contains("X-Amz-Signature") || path.startsWith("http");
                Long size = r.get("size") instanceof Number n ? n.longValue() : null;
                resp.media.add(new Media(
                        path,
                        guessMime(name, type),
                        type,
                        name,
                        isMinio ? "minio" : "local",
                        size
                ));
            }
        } catch (Exception e) {
            log.warn("minioMediaFetchTool degraded: {}", e.toString());
            resp.degraded = true;
            resp.message = "asset-service unreachable: " + e.getClass().getSimpleName();
        }
        return resp;
    }

    private static String guessMime(String name, String type) {
        String low = name == null ? "" : name.toLowerCase();
        if (low.endsWith(".mp4")) return "video/mp4";
        if (low.endsWith(".webm")) return "video/webm";
        if (low.endsWith(".mov")) return "video/quicktime";
        if (low.endsWith(".gif")) return "image/gif";
        if (low.endsWith(".png")) return "image/png";
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) return "image/jpeg";
        if (low.endsWith(".webp")) return "image/webp";
        if (low.endsWith(".svg")) return "image/svg+xml";
        if (low.endsWith(".mp3")) return "audio/mpeg";
        if (low.endsWith(".wav")) return "audio/wav";
        if (low.endsWith(".ogg")) return "audio/ogg";
        return switch (type) {
            case "video" -> "video/*";
            case "audio" -> "audio/*";
            case "photo", "icon", "gif" -> "image/*";
            default -> "application/octet-stream";
        };
    }
}
