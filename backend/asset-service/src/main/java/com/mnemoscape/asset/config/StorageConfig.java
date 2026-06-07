package com.mnemoscape.asset.config;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {
    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    private final StorageProperties properties;

    public StorageConfig(StorageProperties properties) {
        this.properties = properties;
    }

    @Bean
    public MinioClient minioClient() {
        String accessKey = properties.getAccessKey();
        String secretKey = properties.getSecretKey();
        if (!properties.hasCredentials()) {
            log.warn("MINIO_ACCESS_KEY / MINIO_SECRET_KEY is not configured. Asset upload and MinIO listing will fail closed; local static resources remain available.");
            accessKey = "minio-disabled";
            secretKey = "minio-disabled";
        }
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(accessKey, secretKey)
                .build();
    }
}
