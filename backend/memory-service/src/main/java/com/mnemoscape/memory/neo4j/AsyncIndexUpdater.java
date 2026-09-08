package com.mnemoscape.memory.neo4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R16：写后异步索引同步器。
 *
 * <p>背景：Neo4j 的 RTree / 全文 / 向量索引都是 <b>异步填充</b> 的。
 * 一个事务 COMMIT 后，索引项进入 population 队列；立刻查可能命中旧索引
 * （查询计划回退到 NodeByLabelScan，巨慢）。
 *
 * <p>{@code db.awaitIndexesOnline()} / {@code SHOW INDEXES} 会阻塞到所有
 * 索引 ONLINE 后才返回。如果在同步链路里调用它，会拉长 commit 时延；
 * 用 {@code @Async} 把它推到 mem-async-* 线程池（{@code AsyncExecutorConfig}
 * 已配 8/32/200 + CALLER_RUNS），让写入线程立刻返回。
 *
 * <p>该组件由 {@link com.mnemoscape.memory.service.MemoryGraphService}
 * 在每批写完后触发；失败只是降级（warn + 监控），不重试无限次 —— 重试
 * 应由上层 outbox / 调度器承担，索引最终一致性窗口对业务无影响。
 */
@Component
public class AsyncIndexUpdater {

    private static final Logger log = LoggerFactory.getLogger(AsyncIndexUpdater.class);

    private final Optional<Neo4jClient> neo4jClient;
    private final AtomicLong syncCount = new AtomicLong();
    private final AtomicLong lastSyncMillis = new AtomicLong(-1);

    public AsyncIndexUpdater(Optional<Neo4jClient> neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 写完一批后异步调用：
     * <ol>
     *   <li>{@code SHOW INDEXES YIELD name, state, type} —— 拉所有索引快照</li>
     *   <li>对每条 ONLINE 索引不做处理；非 ONLINE → 调一次
     *       {@code db.awaitIndexesOnline()}（默认 10s timeout）</li>
     * </ol>
     *
     * <p>{@code @Async} 跑在 {@code applicationTaskExecutor}（mem-async-*
     * 线程池），队列打满会触发 CALLER_RUNS 背压，不丢任务。
     */
    @Async
    public void awaitIndexesOnline() {
        if (neo4jClient.isEmpty()) return;
        long t0 = System.nanoTime();
        try {
            Neo4jClient client = neo4jClient.get();

            // 1) 列出所有索引状态 —— 这是只读探测
            Collection<Map<String, Object>> indexes = client.query("""
                    SHOW INDEXES YIELD name, state, type
                    """)
                    .fetch()
                    .all();

            boolean needWait = indexes.stream()
                    .anyMatch(m -> !"ONLINE".equals(String.valueOf(m.get("state"))));

            if (!needWait) {
                long elapsed = (System.nanoTime() - t0) / 1_000_000L;
                log.debug("All {} Neo4j indexes already ONLINE ({} ms)",
                        indexes.size(), elapsed);
                syncCount.incrementAndGet();
                lastSyncMillis.set(elapsed);
                return;
            }

            // 2) 有非 ONLINE → 阻塞等待（10 秒封顶，避免无限挂死）
            client.query("CALL db.awaitIndexesOnline('10s')")
                    .run();

            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            syncCount.incrementAndGet();
            lastSyncMillis.set(elapsed);
            log.info("Neo4j indexes synchronized asynchronously: {} total, {} ms",
                    indexes.size(), elapsed);

        } catch (Exception e) {
            // 异步任务里抛异常会被 AsyncUncaughtExceptionHandler 接走并吞掉；
            // 这里再打一条 WARN 方便排障
            log.warn("Async index sync failed (will retry on next batch): {}",
                    e.toString());
        }
    }

    /** 重载：等指定超时（秒） */
    @Async
    public void awaitIndexesOnline(String timeoutSeconds) {
        if (neo4jClient.isEmpty()) return;
        try {
            Neo4jClient client = neo4jClient.get();
            String safe = timeoutSeconds == null || timeoutSeconds.isBlank()
                    ? "10s" : timeoutSeconds;
            client.query("CALL db.awaitIndexesOnline($timeout)")
                    .bindAll(Map.of("timeout", safe))
                    .run();
            syncCount.incrementAndGet();
            log.info("Neo4j indexes synchronized with timeout={}", safe);
        } catch (Exception e) {
            log.warn("Async index sync (custom timeout) failed: {}", e.toString());
        }
    }

    /** 给监控用的指标读取。 */
    public long getSyncCount() { return syncCount.get(); }
    public long getLastSyncMillis() { return lastSyncMillis.get(); }
}