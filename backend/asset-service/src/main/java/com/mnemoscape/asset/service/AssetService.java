package com.mnemoscape.asset.service;

import com.mnemoscape.asset.config.StorageProperties;
import com.mnemoscape.asset.model.StaticResource;
import com.mnemoscape.common.exception.BizException;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AssetService {
    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final MinioClient minioClient;
    private final StorageProperties properties;

    public AssetService(MinioClient minioClient, StorageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public String upload(MultipartFile file) {
        requireMinioCredentials();
        String objectName = UUID.randomUUID() + "-" + file.getOriginalFilename();
        ensureBucket();
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(in, file.getSize(), properties.getPartSize())
                    .contentType(file.getContentType())
                    .build());
            log.info("Uploaded: {}", objectName);
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("Upload failed", e);
        }
    }

    public InputStream download(String objectName) {
        requireMinioCredentials();
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build());
        } catch (ErrorResponseException ex) {
            throw BizException.notFound("Asset", objectName);
        } catch (Exception e) {
            throw new RuntimeException("Download failed", e);
        }
    }

    public String getPresignedUrl(String objectName) {
        requireMinioCredentials();
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .method(Method.GET)
                    .expiry(properties.getPresignedTtlSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate URL", e);
        }
    }

    public void delete(String objectName) {
        requireMinioCredentials();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build());
            log.info("Deleted: {}", objectName);
        } catch (Exception e) {
            throw new RuntimeException("Delete failed", e);
        }
    }

    /**
     * 把 MinIO bucket 里所有对象列出来，按扩展名归类成 video/photo/audio/gif/icon/other。
     * 每次 /static/resources 请求都会拉一次 — MinIO 列表 API 本身轻量，不缓存即"热更新"。
     * 失败时静默返回空列表，确保前端仍能拿到本地资源。
     */
    public List<StaticResource> listMinioStatic() {
        List<StaticResource> out = new ArrayList<>();
        if (!properties.hasCredentials()) {
            log.warn("[AssetService] MinIO credentials are not configured; returning local-only static resources.");
            return out;
        }
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket()).build());
            if (!exists) return out;

            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucket())
                    .recursive(true)
                    .build());

            for (Result<Item> r : results) {
                try {
                    Item it = r.get();
                    if (it.isDir()) continue;
                    String name = it.objectName();
                    String type = classify(name);
                    // 走 presigned 直连 — 比 backend stream 省一次跳，更适合视频/GIF 大文件
                    String url;
                    try {
                        url = getPresignedUrl(name);
                    } catch (Exception ex) {
                        url = "/api/v1/assets/download/" + name;
                    }
                    out.add(new StaticResource(
                            name,
                            url,
                            type,
                            "minio",
                            it.size(),
                            it.lastModified() != null ? it.lastModified().toInstant().toEpochMilli() : 0L
                    ));
                } catch (Exception ignored) { /* skip bad entry */ }
            }
        } catch (Exception e) {
            log.warn("[AssetService] listMinioStatic failed (returning empty): {}", e.getMessage());
        }
        return out;
    }

    private void requireMinioCredentials() {
        if (!properties.hasCredentials()) {
            throw BizException.internalError(
                    "MinIO credentials are not configured. Set MINIO_ACCESS_KEY and MINIO_SECRET_KEY in the host environment.");
        }
    }

    private String classify(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")) return "video";
        if (lower.endsWith(".gif")) return "gif";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac")) return "audio";
        if (lower.endsWith(".svg")) return "icon";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp")) return "photo";
        return "other";
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(properties.getBucket()).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Bucket check failed", e);
        }
    }
}
