package com.mnemoscape.ai.index;

import com.mnemoscape.ai.config.VectorStoreProperties;
import com.mnemoscape.ai.service.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * R19 增量索引器 —— 包装 {@link MilvusVectorStore}，在 upsert 路径上
 * 维护 "dirty 计数"，达到阈值时异步触发一次后台重建。
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li><b>dirty 标记</b>：每次 upsert 成功后将 {@link #dirtyCount} 原子加 1。
 *       失败的 upsert 不计入（避免一次网络抖动触发重建）。</li>
 *   <li><b>阈值触发</b>：{@code dirtyCount >= config.dirtyThreshold} 时
 *       调 {@link #scheduleRebuild(int)} 提交一个后台重建任务。触发后立刻
 *       重置 dirtyCount（避免同一批重复触发），未触发时保留累积值。</li>
 *   <li><b>异步</b>：{@link Async} + 独立线程池（ai-service 已有
 *       {@code ai-async-} 池），不阻塞 upsert 主路径。重建期间在线查询
 *       （{@link MilvusVectorStore#search}）走旧索引，不受影响。</li>
 *   <li><b>并发安全</b>：{@link #dirtyCount} 用 {@link AtomicInteger}；
 *       "触发后清零" 用 {@link ReentrantLock} 串行化，避免 N 个并发 upsert
 *       重复触发重建。</li>
 *   <li><b>HNSW 调优</b>：每次重建都从 {@link MilvusIndexConfig} 拿最新
 *       参数，所以 Nacos 热更新完调参后，下一次 rebuild 自动使用新参数
 *       （无需重启服务）。</li>
 * </ul>
 *
 * <p><b>不修改 MilvusVectorStore</b>：本类用 {@link ObjectProvider} 拿
 * {@link IndexRebuildService}（避免循环依赖），upsert 主路径只调
 * {@link MilvusVectorStore#upsert}，对原代码零侵入。
 */
@Service
public class IncrementalIndexer {

    private static final Logger log = LoggerFactory.getLogger(IncrementalIndexer.class);

    private final MilvusVectorStore vectorStore;
    private final VectorStoreProperties storeProps;
    private final MilvusIndexConfig indexConfig;
    private final IndexVersionRepository versionRepo;
    /** 延迟拿 IndexRebuildService，避免与 IndexRebuildService 互相依赖时启动失败。 */
    private final ObjectProvider<IndexRebuildService> rebuildServiceProvider;

    /** 当前未触发的脏数据条数。触发后清零。 */
    private final AtomicInteger dirtyCount = new AtomicInteger(0);
    /** 累计 dirty 计数（触发后不清零，监控用）。 */
    private final AtomicLong totalDirtySeen = new AtomicLong(0L);
    /** 累计触发的重建次数。 */
    private final AtomicLong totalRebuildsTriggered = new AtomicLong(0L);

    /** 保护"读 dirtyCount → 触发 → 清零"三步的原子性。 */
    private final ReentrantLock triggerLock = new ReentrantLock();

    public IncrementalIndexer(MilvusVectorStore vectorStore,
                              VectorStoreProperties storeProps,
                              MilvusIndexConfig indexConfig,
                              IndexVersionRepository versionRepo,
                              ObjectProvider<IndexRebuildService> rebuildServiceProvider) {
        this.vectorStore = vectorStore;
        this.storeProps = storeProps;
        this.indexConfig = indexConfig;
        this.versionRepo = versionRepo;
        this.rebuildServiceProvider = rebuildServiceProvider;
    }

    /**
     * 写一条记忆向量。失败 → 计入失败、不计入 dirty；成功 → 计入 dirty。
     * 阈值命中 → 异步触发重建。
     *
     * @return true=Milvus upsert 成功（与 {@link MilvusVectorStore#upsert} 一致）
     */
    public boolean upsert(MilvusVectorStore.VectorRecord rec) {
        boolean ok = vectorStore.upsert(rec);
        if (ok) {
            markDirty();
        }
        return ok;
    }

    /**
     * 标记一条脏数据。命中阈值时触发后台重建（仅一次）。线程安全。
     */
    public void markDirty() {
        int current = dirtyCount.incrementAndGet();
        totalDirtySeen.incrementAndGet();
        int threshold = indexConfig.getDirtyThreshold();
        if (current >= threshold) {
            // 用 lock 保证只触发一次；其它并发 caller 看到 dirtyCount=0 后直接退出
            if (triggerLock.tryLock()) {
                try {
                    // 重新检查（拿锁期间可能已被另一个 caller 清零并触发）
                    int now = dirtyCount.get();
                    if (now >= threshold) {
                        int toSchedule = dirtyCount.getAndSet(0);
                        totalRebuildsTriggered.incrementAndGet();
                        log.info("[IncrementalIndexer] dirty threshold hit: count={}, threshold={}, scheduling rebuild",
                                now, threshold);
                        scheduleRebuild(toSchedule);
                    }
                } finally {
                    triggerLock.unlock();
                }
            }
        }
    }

    /**
     * 异步提交一次重建任务。{@link Async} 让调用方立即返回。
     *
     * <p>如果当前已有 RUNNING/PENDING 的重建 → 跳过（不排队），下一次
     * markDirty 达到阈值再触发。
     */
    @Async
    public void scheduleRebuild(int triggeredDirtyCount) {
        String collection = storeProps.getCollectionName();
        if (versionRepo.hasActiveRebuild(collection)) {
            log.info("[IncrementalIndexer] skip schedule: collection={} already has active rebuild", collection);
            return;
        }
        IndexRebuildService svc = rebuildServiceProvider.getIfAvailable();
        if (svc == null) {
            log.warn("[IncrementalIndexer] IndexRebuildService not available, skipping rebuild");
            return;
        }
        try {
            svc.rebuildNow(triggeredDirtyCount);
        } catch (Exception e) {
            log.warn("[IncrementalIndexer] rebuild submission failed: {}", e.toString());
        }
    }

    /* ==================== 状态查询 ==================== */

    public int getDirtyCount() { return dirtyCount.get(); }
    public long getTotalDirtySeen() { return totalDirtySeen.get(); }
    public long getTotalRebuildsTriggered() { return totalRebuildsTriggered.get(); }

    /** 重置 dirty 计数（管理员运维用）。 */
    public void resetDirtyCount() {
        dirtyCount.set(0);
    }
}
