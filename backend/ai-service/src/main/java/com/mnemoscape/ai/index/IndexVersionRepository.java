package com.mnemoscape.ai.index;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * R19 索引版本仓库 —— {@code index_versions} 表的进程内实现。
 *
 * <p><b>表语义</b>：每个 collection 一份版本号（自增），每次触发重建生成一条
 * 新记录。状态机：{@code PENDING → RUNNING → (SUCCESS|FAILED)}，终态后
 * 不再变化。
 *
 * <p><b>并发模型</b>：
 * <ul>
 *   <li>用 {@link ConcurrentHashMap} 存数据，键 = {@code collectionName}，
 *       值 = 该 collection 的所有版本列表（按 version 升序）。</li>
 *   <li>写入用 {@link ReentrantReadWriteLock} 串行化，保证 version 单调
 *       递增 + 状态机原子推进。</li>
 *   <li>读多写少（状态查询远多于触发重建），所以读路径不加锁（{@code Map}
 *       本身已线程安全 + 列表引用稳定）。</li>
 * </ul>
 *
 * <p><b>JPA 迁移路径</b>：接口形态（save / findLatest / findByStatus）与
 * Spring Data {@code JpaRepository} 一致，未来切换时只换实现不动调用方。
 */
@Repository
public class IndexVersionRepository {

    /** 主键自增（模拟 MySQL AUTO_INCREMENT）。 */
    private final AtomicLong idSeq = new AtomicLong(0L);

    /** collectionName → 该 collection 的所有版本（已按 version 升序）。 */
    private final Map<String, List<IndexVersion>> store = new ConcurrentHashMap<>();

    /** 写锁：保护 version 自增 / 状态机切换的原子性。读路径不加。 */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /* ==================== 写 ==================== */

    /**
     * 创建一条 PENDING 记录，version 自动取当前最大 + 1。返回带 id 的实体。
     */
    public IndexVersion createPending(String collectionName, int dirtyCount, String paramsJson) {
        rwLock.writeLock().lock();
        try {
            List<IndexVersion> versions = store.computeIfAbsent(collectionName, k -> new ArrayList<>());
            int nextVersion = versions.isEmpty() ? 1 : versions.get(versions.size() - 1).getVersion() + 1;
            IndexVersion v = new IndexVersion(collectionName, nextVersion, IndexVersion.Status.PENDING, dirtyCount);
            v.setId(idSeq.incrementAndGet());
            v.setParamsJson(paramsJson);
            versions.add(v);
            return v;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 更新状态。状态机规则：
     * <ul>
     *   <li>PENDING → RUNNING / FAILED</li>
     *   <li>RUNNING → SUCCESS / FAILED</li>
     *   <li>SUCCESS / FAILED：终态，不可改</li>
     * </ul>
     *
     * @return true=更新成功；false=记录不存在或状态机非法
     */
    public boolean updateStatus(Long id, IndexVersion.Status newStatus, String errorMessage) {
        if (id == null || newStatus == null) return false;
        rwLock.writeLock().lock();
        try {
            for (List<IndexVersion> versions : store.values()) {
                for (IndexVersion v : versions) {
                    if (id.equals(v.getId())) {
                        IndexVersion.Status cur = v.getStatus();
                        if (cur == IndexVersion.Status.SUCCESS || cur == IndexVersion.Status.FAILED) {
                            return false; // 终态不可改
                        }
                        if (cur == IndexVersion.Status.PENDING && newStatus == IndexVersion.Status.SUCCESS) {
                            return false; // 不可跳过 RUNNING
                        }
                        v.setStatus(newStatus);
                        if (newStatus == IndexVersion.Status.SUCCESS || newStatus == IndexVersion.Status.FAILED) {
                            v.setCompletedAt(java.time.Instant.now());
                        }
                        if (newStatus == IndexVersion.Status.FAILED && errorMessage != null) {
                            v.setErrorMessage(errorMessage);
                        }
                        return true;
                    }
                }
            }
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /* ==================== 读 ==================== */

    /** 按 id 查。 */
    public Optional<IndexVersion> findById(Long id) {
        if (id == null) return Optional.empty();
        for (List<IndexVersion> versions : store.values()) {
            for (IndexVersion v : versions) {
                if (id.equals(v.getId())) return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /** 查某 collection 的最新一条（按 version 降序取第一个）。 */
    public Optional<IndexVersion> findLatest(String collectionName) {
        List<IndexVersion> versions = store.get(collectionName);
        if (versions == null || versions.isEmpty()) return Optional.empty();
        return Optional.of(versions.get(versions.size() - 1));
    }

    /** 查某 collection 的所有版本（按 version 升序）。 */
    public List<IndexVersion> findAllByCollection(String collectionName) {
        List<IndexVersion> versions = store.get(collectionName);
        if (versions == null) return List.of();
        return new ArrayList<>(versions);
    }

    /** 查所有 RUNNING 状态的记录（用于"是否已有重建在跑"互斥判断）。 */
    public List<IndexVersion> findByStatus(IndexVersion.Status status) {
        if (status == null) return List.of();
        List<IndexVersion> result = new ArrayList<>();
        for (List<IndexVersion> versions : store.values()) {
            for (IndexVersion v : versions) {
                if (v.getStatus() == status) result.add(v);
            }
        }
        result.sort(Comparator.comparing(IndexVersion::getCreatedAt));
        return result;
    }

    /** 是否已有 RUNNING / PENDING 状态的重建任务（用于互斥）。 */
    public boolean hasActiveRebuild(String collectionName) {
        List<IndexVersion> versions = store.get(collectionName);
        if (versions == null) return false;
        for (IndexVersion v : versions) {
            if (v.getStatus() == IndexVersion.Status.PENDING || v.getStatus() == IndexVersion.Status.RUNNING) {
                return true;
            }
        }
        return false;
    }

    /* ==================== 维护 ==================== */

    /** 清空全部（仅测试用；生产不应暴露）。 */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            store.clear();
            idSeq.set(0L);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 当前总记录数（监控用）。 */
    public int size() {
        int n = 0;
        for (List<IndexVersion> versions : store.values()) n += versions.size();
        return n;
    }
}
