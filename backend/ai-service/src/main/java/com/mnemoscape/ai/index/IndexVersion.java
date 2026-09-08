package com.mnemoscape.ai.index;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * R19 索引版本记录 —— 对应 {@code index_versions} 表的一行。
 *
 * <p><b>表结构</b>（逻辑表，由 {@link IndexVersionRepository} 内存实现）：
 * <pre>
 *   CREATE TABLE index_versions (
 *     id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
 *     collection_name VARCHAR(128) NOT NULL,
 *     version         INT          NOT NULL,           -- 单调递增的版本号
 *     status          VARCHAR(16)  NOT NULL,           -- PENDING/RUNNING/SUCCESS/FAILED
 *     params_json     TEXT         NULL,               -- 索引参数快照（便于复盘 / 回滚）
 *     dirty_count     INT          NOT NULL DEFAULT 0, -- 触发本次重建的脏数据条数
 *     error_message   TEXT         NULL,               -- FAILED 时记录最后一条异常
 *     created_at      DATETIME(3)  NOT NULL,           -- 记录创建时间
 *     completed_at    DATETIME(3)  NULL,               -- 状态变为终态的时间
 *     UNIQUE KEY uk_collection_version (collection_name, version)
 *   );
 * </pre>
 *
 * <p><b>为什么是 in-memory 而非 MySQL</b>：ai-service 当前没有 MySQL 数据源
 * （只走 Redis / Milvus / NVIDIA），为不引入新的 DataSource 依赖，把
 * {@code index_versions} 作为进程内结构维护；数据量极小（每天最多 1~2 条），
 * 不丢即可（丢了就少一次 rebuild 调度，下个周期自动补上）。如未来要跨实例
 * 协调，换成 JPA + 现有 MySQL 即可，对外接口（{@link IndexVersionRepository}）
 * 不变。
 */
public class IndexVersion {

    /** 重建任务状态。 */
    public enum Status {
        /** 已调度，等待执行。 */
        PENDING,
        /** 正在执行。 */
        RUNNING,
        /** 成功。终态。 */
        SUCCESS,
        /** 失败。终态。 */
        FAILED
    }

    private Long id;
    private String collectionName;
    private int version;
    private Status status;
    /** 索引参数快照（JSON 字符串，便于人工排查 / 审计）。 */
    private String paramsJson;
    /** 触发本次重建的脏数据条数（仅审计用）。 */
    private int dirtyCount;
    private String errorMessage;
    private Instant createdAt;
    private Instant completedAt;

    public IndexVersion() {
    }

    public IndexVersion(String collectionName, int version, Status status, int dirtyCount) {
        this.id = null; // 由 repository 生成
        this.collectionName = Objects.requireNonNull(collectionName, "collectionName");
        this.version = version;
        this.status = status == null ? Status.PENDING : status;
        this.dirtyCount = Math.max(0, dirtyCount);
        this.createdAt = Instant.now();
        this.completedAt = null;
    }

    /** 生成一个新的 id（UUID 简化版；如未来切到 MySQL 则改回自增）。 */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /* ==================== getter / setter ==================== */

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }

    public int getDirtyCount() { return dirtyCount; }
    public void setDirtyCount(int dirtyCount) { this.dirtyCount = dirtyCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    /** 是否为终态（SUCCESS / FAILED）。 */
    public boolean isTerminal() {
        return status == Status.SUCCESS || status == Status.FAILED;
    }

    @Override
    public String toString() {
        return "IndexVersion{" +
                "id=" + id +
                ", collectionName='" + collectionName + '\'' +
                ", version=" + version +
                ", status=" + status +
                ", dirtyCount=" + dirtyCount +
                ", createdAt=" + createdAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
