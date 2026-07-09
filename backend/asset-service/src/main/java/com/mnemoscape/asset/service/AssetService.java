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

    /**
     * MinIO 列表的轻量 TTL 缓存（无外部依赖）。
     *
     * <p>{@code /static/resources} 是前端每次进页面都打的热路径，而它每次都要全桶
     * recursive 扫描 + 给每个对象现生成 presigned URL（N 次 HMAC 签名），素材一多就是
     * 几百 ms。素材的增删频率远低于读取，所以这里按 {@code userId} 缓存结果 30s：
     * presigned URL 有效期是 1h（见 getPresignedUrl），30s 内复用完全安全。
     * 上传 / 删除 / 迁移等写操作会主动 {@link #invalidateListCache()} 清空，保证新素材
     * 最迟下一次请求（或热加载窗口）就能看到。
     */
    private static final long LIST_CACHE_TTL_MS = 30_000L;
    private final java.util.concurrent.ConcurrentHashMap<String, CachedListing> listCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedListing(List<StaticResource> items, long expiresAt) {
        boolean fresh() { return System.currentTimeMillis() < expiresAt; }
    }

    public AssetService(MinioClient minioClient, StorageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /** 写操作后清空列表缓存，避免读到不含新对象的陈旧列表。 */
    public void invalidateListCache() {
        listCache.clear();
    }

    /**
     * 用户私有上传：把 {@code users/{userId}/} 作为 object key 前缀，
     * 让 {@link #listMinioStaticForUser} 能严格隔离不同用户的素材。
     * <p>{@code userId} 必须由调用方（controller）从网关注入的 X-User-Id 获取，
     * 不接受任何用户提示词 / 表单字段覆盖。
     */
    public String uploadForUser(MultipartFile file, String userId) {
        return uploadForUser(file, userId, null);
    }

    public String uploadForUser(MultipartFile file, String userId, String purpose) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(401, "未通过身份认证，请重新登录");
        }
        requireMinioCredentials();
        String safeName = sanitizeFilename(file.getOriginalFilename());
        String subPath = (purpose != null && !purpose.isBlank()) ? purpose.trim() + "/" : "";
        String objectName = USER_PREFIX + userId + "/" + subPath + UUID.randomUUID() + "-" + safeName;
        ensureBucket();
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(in, file.getSize(), properties.getPartSize())
                    .contentType(file.getContentType())
                    .build());
            log.info("Uploaded for user={} purpose={} object={}", userId, purpose, objectName);
            invalidateListCache();
            return objectName;
        } catch (Exception e) {
            throw BizException.internalError("Upload failed", e);
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
            invalidateListCache();
            return objectName;
        } catch (Exception e) {
            throw BizException.internalError("Upload failed", e);
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
            throw BizException.internalError("Download failed", e);
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
            throw BizException.internalError("Failed to generate URL", e);
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
            invalidateListCache();
        } catch (Exception e) {
            throw BizException.internalError("Delete failed", e);
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
        String cacheKey = userId == null ? "__anon__" : userId;
        CachedListing cached = listCache.get(cacheKey);
        if (cached != null && cached.fresh()) {
            return cached.items();
        }
        List<StaticResource> fresh = listMinioStaticInternal(userId, false /* includeAllUsers */);
        listCache.put(cacheKey, new CachedListing(fresh, System.currentTimeMillis() + LIST_CACHE_TTL_MS));
        return fresh;
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

                    // 顶层目录白名单判定：
                    //   1) 根级裸文件（无 /）→ 跳过（防止 random.png 等混入公共列表）
                    //   2) 顶层目录是 users/ → 走 owner 检查（保持原 @Deprecated 兼容）
                    //   3) 顶层目录 ∈ PUBLIC_TOP_LEVEL_DIRS → 放行
                    //   4) 其它（chat/、support/、tickets/、tmp/、__pycache__/、legacy-orphan/ 等）→ 跳过
                    int slash = name.indexOf('/');
                    if (slash < 0) continue; // 根级裸文件：默认不放行
                    String topDir = name.substring(0, slash);

                    if (topDir.equals("users")) {
                        // 私有对象 owner 检查：与原 name.startsWith(USER_PREFIX) 分支语义一致
                        if (!includeAllUsers) {
                             if (userScope == null || !name.startsWith(userScope)) {
                                 continue;
                             }
                             // 过滤掉聊天、客服支持等非记忆存储相关的子目录对象
                             String relativePath = name.substring(userScope.length());
                             if (relativePath.startsWith("chat/") || relativePath.startsWith("support/")) {
                                 continue;
                             }
                        }
                    } else if (StorageProperties.PUBLIC_TOP_LEVEL_DIRS.contains(topDir)) {
                        // 白名单公共对象放行
                    } else {
                        // 顶层目录不在白名单且不是 users/ —— 一律跳过
                        continue;
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

    /**
     * 历史孤儿对象迁移（管理员工具）。
     *
     * <p>背景：早期 {@link #upload(MultipartFile)}（已 @Deprecated）把用户上传对象直接
     * 以 {@code {uuid}-{filename}} 形式写在 bucket 根目录，没有 {@value #USER_PREFIX}
     * 多租户隔离前缀。这些"无前缀"对象会被 {@link #listMinioStaticForUser} 当成
     * "公共内置素材"暴露给所有用户 —— 实际上它们是某个用户的私有上传，属于隐私泄漏面。
     *
     * <p>本方法把这类"疑似历史私有上传"的孤儿对象搬到 {@code legacy-orphan/} 前缀下，
     * 使其不再混进公共素材池。判定规则（保守，避免误伤 133MB 预置素材）：
     * <ul>
     *   <li>跳过已带 {@value #USER_PREFIX} 前缀的对象（已隔离）；</li>
     *   <li>跳过已在 {@value #LEGACY_ORPHAN_PREFIX} 下的对象（已迁移）；</li>
     *   <li>跳过 key 含 {@code /} 的对象（预置素材保留 photo/、video/ 等目录结构）；</li>
     *   <li>仅迁移根级、且文件名形如 {@code <uuid>-...} 的对象（旧 upload() 的指纹）。</li>
     * </ul>
     *
     * @param dryRun true → 只统计与列出候选，不实际移动（让管理员先预览）
     * @return 迁移结果统计 {scanned, candidates, migrated, dryRun, samples}
     */
    public java.util.Map<String, Object> migrateLegacyOrphans(boolean dryRun) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        int scanned = 0, candidates = 0, migrated = 0;
        List<String> samples = new ArrayList<>();

        if (!properties.hasCredentials()) {
            result.put("error", "MinIO credentials not configured");
            result.put("scanned", 0);
            result.put("candidates", 0);
            result.put("migrated", 0);
            result.put("dryRun", dryRun);
            result.put("samples", samples);
            return result;
        }
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket()).build());
            if (!exists) {
                result.put("error", "bucket not found");
                result.put("scanned", 0);
                result.put("candidates", 0);
                result.put("migrated", 0);
                result.put("dryRun", dryRun);
                result.put("samples", samples);
                return result;
            }

            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucket())
                    .recursive(true)
                    .build());

            for (Result<Item> r : results) {
                Item it;
                try { it = r.get(); } catch (Exception e) { continue; }
                if (it.isDir()) continue;
                String name = it.objectName();
                scanned++;

                if (name.startsWith(USER_PREFIX)) continue;          // 已隔离
                if (name.startsWith(LEGACY_ORPHAN_PREFIX)) continue;  // 已迁移
                if (name.contains("/")) continue;                    // 预置素材保留目录结构
                if (!looksLikeLegacyUpload(name)) continue;          // 不是旧 upload() 指纹

                candidates++;
                if (samples.size() < 20) samples.add(name);

                if (!dryRun) {
                    String target = LEGACY_ORPHAN_PREFIX + name;
                    try {
                        // server-side copy 然后删除原对象（MinIO 无原生 move）
                        minioClient.copyObject(CopyObjectArgs.builder()
                                .bucket(properties.getBucket())
                                .object(target)
                                .source(CopySource.builder()
                                        .bucket(properties.getBucket())
                                        .object(name)
                                        .build())
                                .build());
                        minioClient.removeObject(RemoveObjectArgs.builder()
                                .bucket(properties.getBucket())
                                .object(name)
                                .build());
                        migrated++;
                    } catch (Exception e) {
                        log.warn("[migrate] failed to move orphan {} -> {}: {}", name, target, e.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[migrate] migrateLegacyOrphans failed: {}", e.toString());
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        result.put("scanned", scanned);
        result.put("candidates", candidates);
        result.put("migrated", migrated);
        result.put("dryRun", dryRun);
        result.put("samples", samples);
        if (!dryRun && migrated > 0) invalidateListCache();
        log.info("[migrate] legacy-orphan scan done: scanned={} candidates={} migrated={} dryRun={}",
                scanned, candidates, migrated, dryRun);
        return result;
    }

    /** 旧 {@link #upload} 的对象名指纹：{@code <uuid>-<原文件名>}（小写 hex + 连字符 UUID）。 */
    private static boolean looksLikeLegacyUpload(String name) {
        if (name == null || name.length() < 37) return false;
        // UUID 形如 8-4-4-4-12，随后接 '-' 再接原文件名
        String prefix = name.length() > 37 ? name.substring(0, 37) : name;
        // 36 字符 UUID + 第 37 位应为 '-'
        if (name.charAt(36) != '-') return false;
        String uuidPart = name.substring(0, 36);
        return uuidPart.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /**
     * 把所有"非白名单顶层目录"对象搬到 {@code legacy-orphan/{originalPath}}。
     *
     * <p>背景：AssetService 改造后，{@link #listMinioStaticInternal} 不会把
     * {@code chat/}、{@code support/}、{@code tickets/}、{@code tmp/}、{@code __pycache__/} 等
     * 顶层目录里的对象暴露给前端。但这些"被污染"位置的历史对象仍占着 bucket 容量，
     * 管理员需要把它们集中隔离。
     *
     * <p>本方法扫所有非空对象，判定"是否需要迁移"：
     * <ul>
     *   <li>跳过：{@code users/} 前缀（已隔离的私有对象）、{@code legacy-orphan/} 前缀（已迁移）、</li>
     *   <li>跳过：顶层目录 ∈ {@code PUBLIC_TOP_LEVEL_DIRS}（合法公共素材）；</li>
     *   <li>跳过：根级裸文件且形如 {@code <uuid>-...}（属于 {@link #migrateLegacyOrphans} 的工作面）；</li>
     *   <li>候选：根级裸文件不带 UUID-前缀的（如 {@code random.png}），或顶层目录不在白名单也不在
     *       {@code users/}/{@code legacy-orphan/} 内的（如 {@code chat/}、{@code support/} 等）。</li>
     * </ul>
     *
     * @param dryRun true → 只统计与列出候选，不实际移动（让管理员先预览）
     * @return 扫描结果 {scanned, candidates, wouldMigrate, dryRun, samples, error?}
     */
    public java.util.Map<String, Object> migrateOffAllowlist(boolean dryRun) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        int scanned = 0, candidates = 0, wouldMigrate = 0;
        List<String> samples = new ArrayList<>();

        if (!properties.hasCredentials()) {
            result.put("error", "MinIO credentials not configured");
            result.put("scanned", 0);
            result.put("candidates", 0);
            result.put("wouldMigrate", 0);
            result.put("dryRun", dryRun);
            result.put("samples", samples);
            return result;
        }
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket()).build());
            if (!exists) {
                result.put("error", "bucket not found");
                result.put("scanned", 0);
                result.put("candidates", 0);
                result.put("wouldMigrate", 0);
                result.put("dryRun", dryRun);
                result.put("samples", samples);
                return result;
            }

            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucket())
                    .recursive(true)
                    .build());

            for (Result<Item> r : results) {
                Item it;
                try { it = r.get(); } catch (Exception e) { continue; }
                if (it.isDir()) continue;
                String name = it.objectName();
                scanned++;

                if (name.startsWith(USER_PREFIX)) continue;          // 已隔离的私有对象
                if (name.startsWith(LEGACY_ORPHAN_PREFIX)) continue;  // 已迁移

                int slash = name.indexOf('/');
                if (slash < 0) {
                    // 根级裸文件：留给 migrateLegacyOrphans（带 UUID 前缀的那种）处理
                    if (looksLikeLegacyUpload(name)) continue;
                    // 根级裸文件且不带 UUID-前缀（如 random.png）→ 也算污染
                    candidates++;
                    if (samples.size() < 20) samples.add(name);
                    if (!dryRun) {
                        moveToLegacyOrphan(name);
                        wouldMigrate++;
                    }
                    continue;
                }

                String topDir = name.substring(0, slash);
                if (StorageProperties.PUBLIC_TOP_LEVEL_DIRS.contains(topDir)) {
                    continue; // 合法公共目录
                }
                // 顶层目录在白名单外（chat/、support/、tickets/、tmp/、__pycache__/ 等）→ 候选
                candidates++;
                if (samples.size() < 20) samples.add(name);
                if (!dryRun) {
                    moveToLegacyOrphan(name);
                    wouldMigrate++;
                }
            }
        } catch (Exception e) {
            log.warn("[migrate] migrateOffAllowlist failed: {}", e.toString());
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        result.put("scanned", scanned);
        result.put("candidates", candidates);
        result.put("wouldMigrate", wouldMigrate);
        result.put("dryRun", dryRun);
        result.put("samples", samples);
        if (!dryRun && wouldMigrate > 0) invalidateListCache();
        log.info("[migrate] off-allowlist scan done: scanned={} candidates={} wouldMigrate={} dryRun={}",
                scanned, candidates, wouldMigrate, dryRun);
        return result;
    }

    /** 把对象从原 key 搬到 {@code legacy-orphan/{原 key}}（MinIO 无原生 move）。 */
    private void moveToLegacyOrphan(String originalName) {
        String target = LEGACY_ORPHAN_PREFIX + originalName;
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(target)
                    .source(CopySource.builder()
                            .bucket(properties.getBucket())
                            .object(originalName)
                            .build())
                    .build());
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(originalName)
                    .build());
        } catch (Exception e) {
            log.warn("[migrate] failed to move off-allowlist {} -> {}: {}", originalName, target, e.toString());
        }
    }

    /** 历史孤儿对象迁移目标前缀。 */
    public static final String LEGACY_ORPHAN_PREFIX = "legacy-orphan/";

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
            throw BizException.internalError("Bucket check failed", e);
        }
    }
}
