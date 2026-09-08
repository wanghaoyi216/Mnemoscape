package com.mnemoscape.asset.controller;

import com.mnemoscape.asset.model.StaticResource;
import com.mnemoscape.asset.service.AssetService;
import com.mnemoscape.asset.service.LocalResourceWatcher;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.ratelimit.RateLimit;
import com.mnemoscape.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {
    private final AssetService assetService;
    private final LocalResourceWatcher localResourceWatcher;

    public AssetController(AssetService assetService, LocalResourceWatcher localResourceWatcher) {
        this.assetService = assetService;
        this.localResourceWatcher = localResourceWatcher;
    }

    @RateLimit(key = "asset:upload", limit = 10, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "上传过于频繁，请稍后再试")
    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                                    @RequestParam(value = "purpose", required = false) String purpose,
                                                    HttpServletRequest request) {
        // 必须身份感知：每个用户上传的对象都加 users/{userId}/ 前缀，互相不可见
        String userId = RequestContext.requireUserId(request);
        String objectName = assetService.uploadForUser(file, userId, purpose);
        String url = assetService.getPresignedUrl(objectName);
        return ApiResponse.success(Map.of("objectName", objectName, "url", url));
    }

    @GetMapping("/download/{*objectName}")
    public void download(@PathVariable String objectName,
                         HttpServletRequest request,
                         HttpServletResponse response) {
        // 公共素材（不带 users/ 前缀）任何人可读；私有素材只能本人读
        assetService.checkReadPermission(objectName, RequestContext.optionalUserId(request));
        try (InputStream in = assetService.download(objectName)) {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            in.transferTo(response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            throw new RuntimeException("Download failed", e);
        }
    }

    @GetMapping("/{objectName}/url")
    public ApiResponse<Map<String, String>> presignedUrl(@PathVariable String objectName,
                                                          HttpServletRequest request) {
        assetService.checkReadPermission(objectName, RequestContext.optionalUserId(request));
        return ApiResponse.success(Map.of("url", assetService.getPresignedUrl(objectName)));
    }

    @DeleteMapping("/{objectName}")
    public ApiResponse<Void> delete(@PathVariable String objectName,
                                     HttpServletRequest request) {
        // 删除是写操作：必须登录，且只能删除自己的对象
        String userId = RequestContext.requireUserId(request);
        assetService.checkWritePermission(objectName, userId);
        assetService.delete(objectName);
        return ApiResponse.success(null);
    }

    @GetMapping("/static/resources")
    public ApiResponse<List<StaticResource>> getStaticResources(HttpServletRequest request) {
        // 本地静态资源（WatchService 热更新）+ MinIO 对象（按调用者身份过滤）
        // - 匿名（未登录）：只返回公共素材（不带 users/ 前缀）+ 本地 resource/
        // - 已登录：上面 + users/{当前userId}/ 前缀下自己的对象
        String userId = RequestContext.optionalUserId(request);
        List<StaticResource> merged = new java.util.ArrayList<>(localResourceWatcher.getResources());
        try {
            merged.addAll(assetService.listMinioStaticForUser(userId));
        } catch (Exception e) {
            log.warn("[static] MinIO list failed, returning local-only: {}", e.toString());
        }
        return ApiResponse.success(merged);
    }

    @GetMapping("/static/{type}/{filename}")
    public void getStaticFile(@PathVariable String type, @PathVariable String filename, HttpServletResponse response) {
        try {
            Path file = localResourceWatcher.getResourceDir().resolve(type).resolve(filename).normalize();
            // 安全：阻止 ../ 越出资源目录
            if (!file.startsWith(localResourceWatcher.getResourceDir())) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            if (!Files.exists(file) || Files.isDirectory(file)) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            // Set basic content type based on extension
            String mimeType = Files.probeContentType(file);
            if (mimeType == null) {
                String low = filename.toLowerCase();
                if (low.endsWith(".mp4")) mimeType = "video/mp4";
                else if (low.endsWith(".webm")) mimeType = "video/webm";
                else if (low.endsWith(".mov")) mimeType = "video/quicktime";
                else if (low.endsWith(".mp3")) mimeType = "audio/mpeg";
                else if (low.endsWith(".wav")) mimeType = "audio/wav";
                else if (low.endsWith(".ogg")) mimeType = "audio/ogg";
                else if (low.endsWith(".gif")) mimeType = "image/gif";
                else if (low.endsWith(".png")) mimeType = "image/png";
                else if (low.endsWith(".webp")) mimeType = "image/webp";
                else if (low.endsWith(".svg")) mimeType = "image/svg+xml";
                else if (low.endsWith(".jpg") || low.endsWith(".jpeg")) mimeType = "image/jpeg";
                else mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }

            response.setContentType(mimeType);
            response.setContentLengthLong(Files.size(file));
            // 让浏览器/CDN 能缓存命中，但允许快速失效（热更新文件会改变 lastModified）
            response.setHeader("Cache-Control", "public, max-age=60, must-revalidate");

            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                response.flushBuffer();
            }
        } catch (Exception e) {
            log.warn("[static] serve failed for {}/{}: {}", type, filename, e.toString());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}

