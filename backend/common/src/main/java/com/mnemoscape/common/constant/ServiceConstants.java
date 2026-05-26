package com.mnemoscape.common.constant;

public final class ServiceConstants {

    private ServiceConstants() {}

    public static final String SERVICE_AUTH = "auth-service";
    public static final String SERVICE_MEMORY = "memory-service";
    public static final String SERVICE_AI = "ai-service";
    public static final String SERVICE_RESONANCE = "resonance-service";
    public static final String SERVICE_ASSET = "asset-service";

    // RabbitMQ exchanges
    public static final String EXCHANGE_MEMORY = "mnemoscape.memory";
    public static final String EXCHANGE_AI = "mnemoscape.ai";
    public static final String EXCHANGE_NOTIFICATION = "mnemoscape.notification";

    // Routing keys
    public static final String RK_MEMORY_CREATED = "memory.created";
    public static final String RK_MEMORY_UPDATED = "memory.updated";
    public static final String RK_MEMORY_DELETED = "memory.deleted";
    public static final String RK_DRIFT_UPDATED = "memory.drift.updated";

    // MinIO
    public static final String MINIO_BUCKET = "mnemoscape-assets";
}
