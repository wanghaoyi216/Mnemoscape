package com.mnemoscape.common.constant;

public final class ServiceConstants {

    private ServiceConstants() {}

    public static final String SERVICE_AUTH = "auth-service";
    public static final String SERVICE_MEMORY = "memory-service";
    public static final String SERVICE_AI = "ai-service";
    public static final String SERVICE_RESONANCE = "resonance-service";
    public static final String SERVICE_ASSET = "asset-service";

    // ======================================================================
    // RabbitMQ 拓扑 (与 scripts/remote/rabbitmq/definitions.json 一一对应)
    // ----------------------------------------------------------------------
    // 单 topic exchange + DLX 模式。Routing key 命名约定: <domain>.<entity>.<action>
    //
    // Producer 端: memory-service 在记忆/漂流瓶/成就事件落库后立刻 publish.
    // Consumer 端:
    //   - ai-service          监听 ai.memory.index  / ai.memory.evict (向量索引 + 图谱)
    //   - resonance-service   监听 resonance.driftbottle / resonance.achievement
    //                              / resonance.memory.evict (大厅缓存失效)
    //
    // 失败处理: 队列绑定 DLX, TTL 600s 后投递到 dlq, 由 admin 后台兜底人工处理.
    // ======================================================================

    /** 业务事件主交换机 (topic) */
    public static final String EVENTS_EXCHANGE = "mnemoscape.events";
    /** 死信交换机 */
    public static final String EVENTS_DLX = "mnemoscape.events.dlx";
    /** 死信队列 */
    public static final String EVENTS_DLQ = "mnemoscape.events.dlq";

    // -------------------- Routing keys --------------------
    /** 记忆创建/更新成功 → 触发异步向量索引 + Neo4j 图谱写入 */
    public static final String RK_MEMORY_INDEXED = "memory.indexed";
    /** 记忆删除 → 触发异步清向量 + 各类缓存失效 */
    public static final String RK_MEMORY_DELETED = "memory.deleted";
    /** 漂流瓶投放 → 推送到共鸣大厅, 失效大厅 feed 缓存 */
    public static final String RK_DRIFTBOTTLE_THROWN = "driftbottle.thrown";
    /** 漂流瓶被捞起 → 互动通知 + 缓存失效 */
    public static final String RK_DRIFTBOTTLE_PICKED = "driftbottle.picked";
    /** 成就解锁 → 通知 + 共鸣大厅 top-contributors 缓存失效 */
    public static final String RK_ACHIEVEMENT_UNLOCKED = "achievement.unlocked";

    // -------------------- Queues (Consumer 侧) --------------------
    public static final String QUEUE_AI_MEMORY_INDEX = "ai.memory.index";
    public static final String QUEUE_AI_MEMORY_EVICT = "ai.memory.evict";
    public static final String QUEUE_RESONANCE_DRIFTBOTTLE = "resonance.driftbottle";
    public static final String QUEUE_RESONANCE_ACHIEVEMENT = "resonance.achievement";
    public static final String QUEUE_RESONANCE_MEMORY_EVICT = "resonance.memory.evict";

    // -------------------- 兼容别名 (deprecated, 不要在新代码中使用) --------------------
    /** @deprecated 使用 {@link #EVENTS_EXCHANGE} 替代 */
    @Deprecated public static final String EXCHANGE_MEMORY = EVENTS_EXCHANGE;
    /** @deprecated 使用 {@link #EVENTS_EXCHANGE} 替代 */
    @Deprecated public static final String EXCHANGE_AI = EVENTS_EXCHANGE;
    /** @deprecated 使用 {@link #EVENTS_EXCHANGE} 替代 */
    @Deprecated public static final String EXCHANGE_NOTIFICATION = EVENTS_EXCHANGE;
    /** @deprecated 用 {@link #RK_MEMORY_INDEXED} 替代 */
    @Deprecated public static final String RK_MEMORY_CREATED = RK_MEMORY_INDEXED;
    /** @deprecated 用 {@link #RK_MEMORY_INDEXED} 替代 */
    @Deprecated public static final String RK_MEMORY_UPDATED = RK_MEMORY_INDEXED;
    /** @deprecated 漂流瓶事件已细分: 用 {@link #RK_DRIFTBOTTLE_THROWN}/{@link #RK_DRIFTBOTTLE_PICKED} */
    @Deprecated public static final String RK_DRIFT_UPDATED = RK_DRIFTBOTTLE_THROWN;

    // MinIO
    public static final String MINIO_BUCKET = "mnemoscape-assets";
}
