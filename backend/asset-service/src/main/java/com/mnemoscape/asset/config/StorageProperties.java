package com.mnemoscape.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minio")
public class StorageProperties {
    private String endpoint = "http://localhost:9000";
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "mnemoscape-assets";
    private long partSize = 5 * 1024 * 1024;
    private int maxConnections = 20;
    private int presignedTtlSeconds = 3600;

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
