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

    /** 私有素材前缀。任何 key 以 {@code users/} 开头都视为某用户的私有对象，
     *  不带此前缀的 key 视为"公共内置素材"（项目预置 133MB resource 同步过来的）。 */
    public static final String USER_PREFIX = "users/";

    private final MinioClient minioClient;
    private final StorageProperties properties;

    public AssetService(MinioClient minioClient, StorageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /**
     * 用户私有上传：把 {@code users/{userId}/} 作为 object key 前缀，
     * 让 {@link #listMinioStaticForUser} 能严格隔离不同用户的素材。
     * <p>{@code userId} 必须由调用方（controller）从网关注入的 X-User-Id 获取，
     * 不接受任何用户提示词 / 表单字段覆盖。
     */
    public String uploadForUser(MultipartFile file, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(401, "未通过身份认证，请重新登录");
        }
        requireMinioCredentials();
        String safeName = sanitizeFilename(file.getOriginalFilename());
        String objectName = USER_PREFIX + userId + "/" + UUID.randomUUID() + "-" + safeName;
        ensureBucket();
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(in, file.getSize(), properties.getPartSize())
                    .contentType(file.getContentType())
                    .build());
            log.info("Uploaded for user={} object={}", userId, objectName);
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("Upload failed", e);
        }
    }

    /**
     * 历史接口：保留以兼容旧调用方。新路径请走 {@link #uploadForUser}。
     * @deprecated 仅用于无法获取用户身份的内部回填脚本；不要用于 controller。
     */
    @Deprecated
    public String upload(MultipartFile file) {
        requireMinioCredentials();
        String objectName = UUID.randomUUID() + "-" + sanitizeFilename(file.getOriginalFilename());
        ensureBucket();
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(in, file.getSize(), properties.getPartSize())
                    .contentType(file.getContentType())
                    .build());
            log.info("Uploaded (legacy, public): {}", objectName);
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
     *
     * @deprecated 公共 + 全部用户混在一起；新路径请走 {@link #listMinioStaticForUser}。
     */
    @Deprecated
    public List<StaticResource> listMinioStatic() {
        return listMinioStaticInternal(null, true /* includeAllUsers */);
    }

    /**
     * 用户感知版本：
     * <ul>
     *   <li>{@code userId == null} → 只返回公共素材（不带 users/ 前缀）</li>
     *   <li>{@code userId != null} → 公共素材 + 仅该用户 users/{userId}/ 下的私有对象</li>
     * </ul>
     */
    public List<StaticResource> listMinioStaticForUser(String userId) {
        return listMinioStaticInternal(userId, false /* includeAllUsers */);
    }

    private List<StaticResource> listMinioStaticInternal(String userId, boolean includeAllUsers) {
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

            String userScope = userId == null ? null : USER_PREFIX + userId + "/";

            for (Result<Item> r : results) {
                try {
                    Item it = r.get();
                    if (it.isDir()) continue;
                    String name = it.objectName();

                    // 身份过滤：私有对象只返回给所有者；其他用户私有对象一律隐藏
                    if (!includeAllUsers && name.startsWith(USER_PREFIX)) {
                        if (userScope == null || !name.startsWith(userScope)) {
                            continue;
                        }
                    }

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

    /**
     * 读权限：
     * <ul>
     *   <li>公共素材（不以 {@value #USER_PREFIX} 开头）任何调用者均可读，包括匿名</li>
     *   <li>私有素材只允许所有者读，否则抛 403</li>
     * </ul>
     */
    public void checkReadPermission(String objectName, String currentUserId) {
        if (objectName == null || !objectName.startsWith(USER_PREFIX)) return;
        String[] parts = objectName.split("/", 3);
        if (parts.length < 3) return; // 不规范的 key 当成公共对象处理
        String ownerId = parts[1];
        if (currentUserId == null || !currentUserId.equals(ownerId)) {
            throw new BizException(403, "无权访问该资源");
        }
    }

    /** 写权限（删除/覆盖）：必须是对象所有者；公共素材一律拒绝写。 */
    public void checkWritePermission(String objectName, String currentUserId) {
        if (objectName == null || !objectName.startsWith(USER_PREFIX)) {
            throw new BizException(403, "公共内置素材不可修改");
        }
        String[] parts = objectName.split("/", 3);
        if (parts.length < 3) {
            throw new BizException(400, "无效的资源路径");
        }
        String ownerId = parts[1];
        if (!ownerId.equals(currentUserId)) {
            throw new BizException(403, "无权操作他人资源");
        }
    }

    /** 文件名清洗：去掉路径分隔与控制字符，避免 object key 注入或重名命中 USER_PREFIX。 */
    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) return "unnamed";
        // 去掉路径片段，仅保留文件名末段
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        // 替换控制字符与 MinIO 不友好的字符
        name = name.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").trim();
        if (name.isBlank()) return "unnamed";
        return name;
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
