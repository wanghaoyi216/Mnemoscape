package com.mnemoscape.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 配置属性 (MemoryMinioProperties)。
 * 用于 memory-service 的 ChatMemory 冷数据归档存储。
 */
@ConfigurationProperties(prefix = "minio")
public class MemoryMinioProperties {

    private String endpoint = "http://localhost:9000";
    private String accessKey = "";
    private String secretKey = "";
    private String archiveBucket = "memory-archive";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getArchiveBucket() {
        return archiveBucket;
    }

    public void setArchiveBucket(String archiveBucket) {
        this.archiveBucket = archiveBucket;
    }

    public boolean hasCredentials() {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
