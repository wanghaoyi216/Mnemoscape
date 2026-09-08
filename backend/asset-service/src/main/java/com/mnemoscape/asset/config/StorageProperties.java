package com.mnemoscape.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MinIO / 资产存储配置。
 *
 * <p>{@link RefreshScope} 让 Nacos config 推送时此 bean 被销毁重建，切换 endpoint /
 * accessKey / bucket 不需要重启 asset-service。
 */
@Configuration
@RefreshScope
@ConfigurationProperties(prefix = "minio")
public class StorageProperties {
    private String endpoint = "http://localhost:9000";
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "mnemoscape-assets";
    private long partSize = 5 * 1024 * 1024;
    private int maxConnections = 20;
    private int presignedTtlSeconds = 3600;

    /**
     * 公共素材顶层目录白名单。这些目录是项目预置的 133MB resource 同步后的合法位置；
     * 其它顶层目录（如 {@code chat/}、{@code support/}、{@code tickets/}、{@code tmp/}、{@code __pycache__/}
     * 等）属于"被污染"位置，AssetService 在列出 MinIO 公共对象时应一律跳过。
     *
     * <p>注：此字段不持久化到 {@code application.yml}——它属于产品安全策略而非部署配置，
     * 保持编译期常量便于审计与代码检索。
     *
     * <p><b>v8 扩展</b>：新增 5 个分类以匹配 4 类新资源：
     * <ul>
     *   <li>{@code sticker} — 贴纸 / 表情包大图（≥200×200 PNG）</li>
     *   <li>{@code kaomoji} — 颜文字 art（SVG 矢量）</li>
     *   <li>{@code emoji} — Unicode 表情字符（JSON 元数据 + glyph 字形）</li>
     *   <li>{@code avatar} — 默认头像池（用户未上传头像时的兜底）</li>
     *   <li>{@code theme} — 主题背景图（用于 AppHeader 背景轮询）</li>
     * </ul>
     */
    public static final Set<String> PUBLIC_TOP_LEVEL_DIRS =
            Set.of("photo", "video", "audio", "gif", "music", "icon", "icons",
                    "sticker", "kaomoji", "emoji", "avatar", "theme");

    /**
     * 判定一个 MinIO object key 是否属于"公共预置素材"。
     * 仅当 {@code objectName} 以 {@link #PUBLIC_TOP_LEVEL_DIRS} 中某个目录 + {@code "/"} 开头时返回 true。
     *
     * <p>判定细则：
     * <ul>
     *   <li>根级裸文件（不含 {@code /}）→ false（不是预置目录结构）</li>
     *   <li>顶层目录是白名单之一 → true</li>
     *   <li>其它（{@code chat/}、{@code users/}、{@code legacy-orphan/} 等）→ false</li>
     * </ul>
     */
    public static boolean isPublicObject(String objectName) {
        if (objectName == null) return false;
        int slash = objectName.indexOf('/');
        if (slash < 0) return false; // 根级裸文件不算公共对象
        String topDir = objectName.substring(0, slash);
        return PUBLIC_TOP_LEVEL_DIRS.contains(topDir);
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public long getPartSize() { return partSize; }
    public void setPartSize(long partSize) { this.partSize = partSize; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    public int getPresignedTtlSeconds() { return presignedTtlSeconds; }
    public void setPresignedTtlSeconds(int presignedTtlSeconds) { this.presignedTtlSeconds = presignedTtlSeconds; }

    public boolean hasCredentials() {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
