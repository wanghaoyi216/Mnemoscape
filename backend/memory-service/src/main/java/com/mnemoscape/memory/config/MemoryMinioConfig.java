package com.mnemoscape.memory.config;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置 (MemoryMinioConfig)。
 */
@Configuration
@EnableConfigurationProperties(MemoryMinioProperties.class)
public class MemoryMinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryMinioConfig.class);

    private final MemoryMinioProperties properties;

    public MemoryMinioConfig(MemoryMinioProperties properties) {
        this.properties = properties;
    }

    @Bean
    public MinioClient minioClient() {
        String accessKey = properties.getAccessKey();
        String secretKey = properties.getSecretKey();
        if (!properties.hasCredentials()) {
            log.warn("MinIO accessKey/secretKey is not configured for memory-service. MinIO archive tier will operate with fallback credentials.");
            accessKey = "minioadmin";
            secretKey = "minioadmin";
        }
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(accessKey, secretKey)
                .build();
    }
}
