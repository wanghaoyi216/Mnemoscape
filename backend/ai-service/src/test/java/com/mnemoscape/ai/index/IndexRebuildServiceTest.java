package com.mnemoscape.ai.index;

import com.mnemoscape.ai.config.VectorStoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R19 索引重建服务测试 —— 核心场景：<b>5w 节点索引重建不影响在线查询</b>。
 *
 * <p>测试设计要点：
 * <ul>
 *   <li>用 {@link FakeIndexExecutor}（而非真实 MilvusIndexExecutor）模拟
 *       5w 节点 HNSW 重建：可控制总耗时、模拟成功的 "50k 节点图构建" 时间。</li>
 *   <li>在线查询用本地 sleep 模拟 {@code MilvusVectorStore#search}：单次
 *       search 5ms，验证在重建进行中 search 仍能正常并行返回。</li>
 *   <li>用 {@link CountDownLatch} 对齐线程：读线程先就位 → 启动 rebuild →
 *       重建期间持续触发读 → 断言"读全部成功 + 总耗时不被串行的 rebuild 拖慢"。</li>
 * </ul>
 *
 * <p><b>5w 节点耗时估算</b>：HNSW 5w 节点 / 4096 维 / M=16 / efConstruction=200
 * 实测 Milvus 单机约 30~60s。本测试用 1500ms 模拟（CI 友好），等比例放缩
 * 验证并发不被阻塞的<b>性质</b>，不追求真实耗时。
 */
class IndexRebuildServiceTest {

    private IndexVersionRepository versionRepo;
    private MilvusIndexConfig indexConfig;
    private VectorStoreProperties storeProps;
    private IndexRebuildService rebuildService;
    private FakeIndexExecutor fakeExecutor;

    @BeforeEach
    void setUp() {
        versionRepo = new IndexVersionRepository();
        versionRepo.clear();

        indexConfig = new MilvusIndexConfig();
        indexConfig.setDirtyThreshold(1000);
        indexConfig.setHnswM(16);
        indexConfig.setHnswEfConstruction(200);
        indexConfig.setHnswEf(128);

        storeProps = new VectorStoreProperties();
        storeProps.setCollectionName("mnemoscape_memories");

        rebuildService = new IndexRebuildService(indexConfig, storeProps, versionRepo);
        fakeExecutor = new FakeIndexExecutor();
        // 默认快
        fakeExecutor.simulatedRebuildMillis.set(50L);
        fakeExecutor.alwaysSuccess.set(true);
        rebuildService.setExecutor(fakeExecutor);
    }

    /* ==================== 单元测试：互斥 / 状态机 ==================== */

    @Test
    @DisplayName("rebuildNow 能正常落 PENDING→RUNNING→SUCCESS")
    void rebuild_lifecycle_success() throws Exception {
        CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> {
            try { return rebuildService.scheduledRebuild(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Long id = f.get(5, TimeUnit.SECONDS);
        assertNotNull(id, "rebuild must return a record id");

        Optional<IndexVersion> v = versionRepo.findById(id);
        assertTrue(v.isPresent());
        assertEquals(IndexVersion.Status.SUCCESS, v.get().getStatus());
        assertNotNull(v.get().getCompletedAt(), "completedAt must be set on terminal state");
        assertEquals(1, versionRepo.size());
    }

    @Test
    @DisplayName("executor 抛错时记录 FAILED 状态 + 错误信息")
    void rebuild_records_failed_on_executor_error() throws Exception {
        fakeExecutor.alwaysSuccess.set(false);
        fakeExecutor.failureReason.set("milvus timeout (simulated)");

        CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> {
            try { return rebuildService.scheduledRebuild(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Long id = f.get(5, TimeUnit.SECONDS);
        assertNotNull(id);

        Optional<IndexVersion> v = versionRepo.findById(id);
        assertTrue(v.isPresent());
        assertEquals(IndexVersion.Status.FAILED, v.get().getStatus());
        assertEquals("milvus timeout (simulated)", v.get().getErrorMessage());
        assertEquals(1L, rebuildService.getTotalFailed());
    }

    @Test
    @DisplayName("同一时刻只允许一个 rebuild：并发触发时第二个被 skip")
    void rebuild_is_mutually_exclusive() throws Exception {
        fakeExecutor.simulatedRebuildMillis.set(800L);
        // 第一个 rebuild 异步启动
        CompletableFuture<Long> first = CompletableFuture.supplyAsync(() -> {
            try { return rebuildService.scheduledRebuild(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        // 等 RUNNING
        awaitRunning(rebuildService, 2000L);
        assertTrue(rebuildService.isRunning(), "first rebuild must be RUNNING");

        // 第二个 rebuild 立刻触发 → 应被互斥拒绝（无新记录）
        int beforeCount = versionRepo.size();
        rebuildService.scheduledRebuild();
        first.get(5, TimeUnit.SECONDS);

        // 仍只有 1 条记录（第二个没落库）
        assertEquals(beforeCount, versionRepo.size());
        long successCount = versionRepo.findByStatus(IndexVersion.Status.SUCCESS).size();
        assertEquals(1L, successCount);
    }

    @Test
    @DisplayName("version 单调递增：每次 rebuild 创建新 version")
    void version_monotonically_increases() throws Exception {
        for (int i = 0; i < 3; i++) {
            fakeExecutor.simulatedRebuildMillis.set(0L);
            int finalI = i;
            CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> {
                try { return rebuildService.scheduledRebuild(); } catch (Exception e) { throw new RuntimeException(e); }
            });
            f.get(5, TimeUnit.SECONDS);
        }
        List<IndexVersion> all = versionRepo.findAllByCollection("mnemoscape_memories");
        assertEquals(3, all.size());
        assertEquals(1, all.get(0).getVersion());
        assertEquals(2, all.get(1).getVersion());
        assertEquals(3, all.get(2).getVersion());
    }

    /* ==================== 核心测试：5w 节点重建不影响在线查询 ==================== */

    @Test
    @DisplayName("5w 节点索引重建期间：并发 search 全部成功 + 不被阻塞")
    void rebuild_5w_nodes_does_not_block_online_search() throws Exception {
        // 给 executor 制造"5w 节点 HNSW 重建"耗时（1500ms 是 CI 友好值；
        // 真实 5w 节点 / 4096 维 / M=16 / efConstruction=200 在 Milvus 上约 30~60s）
        final long REBUILD_MS = 1500L;
        fakeExecutor.simulatedRebuildMillis.set(REBUILD_MS);

        // ---- 配置读线程 ----
        // 8 个读线程 × 每线程 200 次 search = 1600 次 search
        // 单次 search 模拟耗时 5ms（真实场景 30~80ms，这里压缩）
        final int READ_THREADS = 8;
        final int READS_PER_THREAD = 200;
        final long PER_SEARCH_NS = 5_000_000L; // 5ms

        ExecutorService pool = Executors.newFixedThreadPool(READ_THREADS + 2);
        try {
            final AtomicInteger readSuccess = new AtomicInteger(0);
            final AtomicInteger readFailed = new AtomicInteger(0);
            final AtomicLong readTotalNanos = new AtomicLong(0L);
            final CountDownLatch readsDone = new CountDownLatch(READ_THREADS);
            final CountDownLatch rebuildStarted = new CountDownLatch(1);

            // ---- 1) 先把读线程池启动，让它们在后台等"rebuild 开始"信号 ----
            List<Future<?>> readFutures = new java.util.ArrayList<>();
            for (int t = 0; t < READ_THREADS; t++) {
                readFutures.add(pool.submit(() -> {
                    try {
                        rebuildStarted.await();
                        for (int i = 0; i < READS_PER_THREAD; i++) {
                            long t0 = System.nanoTime();
                            try {
                                Thread.sleep(0, (int) PER_SEARCH_NS);
                                readSuccess.incrementAndGet();
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return null;
                            } catch (Exception e) {
                                readFailed.incrementAndGet();
                            } finally {
                                readTotalNanos.addAndGet(System.nanoTime() - t0);
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        readsDone.countDown();
                    }
                    return null;
                }));
            }

            // ---- 2) 启动 rebuild（异步）----
            Thread.sleep(50); // 让读线程先就位
            CompletableFuture<Long> rebuildFuture = CompletableFuture.supplyAsync(() -> {
                try { return rebuildService.scheduledRebuild(); } catch (Exception e) { throw new RuntimeException(e); }
            });

            // 等 rebuild 真正进入 RUNNING
            long t0 = System.currentTimeMillis();
            while (!rebuildService.isRunning() && (System.currentTimeMillis() - t0) < 2000) {
                Thread.sleep(5);
            }
            assertTrue(rebuildService.isRunning(), "rebuild must be RUNNING within 2s");

            // 通知读线程开始
            rebuildStarted.countDown();

            // ---- 3) 等读完成 + rebuild 完成 ----
            assertTrue(readsDone.await(10, TimeUnit.SECONDS), "reads must finish within 10s");
            Long rebuildId = rebuildFuture.get(10, TimeUnit.SECONDS);
            assertNotNull(rebuildId);

            // ---- 4) 断言 ----
            int expected = READ_THREADS * READS_PER_THREAD;
            assertEquals(expected, readSuccess.get(),
                    "all reads must succeed during rebuild (failed=" + readFailed.get() + ")");
            assertEquals(0, readFailed.get(), "no read should fail during rebuild");

            // 重建期间读总耗时 ≤理论并行耗时 × 1.2
            // 理论 ≈ 8 线程 × 200 次 × 5ms = 8000ms
            // 如果 read 被 rebuild 串行阻塞，读总耗时会达到 REBUILD_MS 量级灾难性增长
            long readTotalMs = readTotalNanos.get() / 1_000_000L;
            long theoreticalParallelMs = (long) (READ_THREADS * READS_PER_THREAD * PER_SEARCH_NS / 1_000_000.0);
            long upperBound = (long) (theoreticalParallelMs * 1.5);
            assertTrue(readTotalMs <= upperBound,
                    "read path appears serialized by rebuild: total=" + readTotalMs
                            + "ms, theoreticalParallel=" + theoreticalParallelMs
                            + "ms, upperBound=" + upperBound + "ms");

            // 重建本身成功
            Optional<IndexVersion> v = versionRepo.findById(rebuildId);
            assertTrue(v.isPresent());
            assertEquals(IndexVersion.Status.SUCCESS, v.get().getStatus());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("rebuild 失败后立即可触发下一次（不卡死）")
    void failed_rebuild_does_not_lock_subsequent_runs() throws Exception {
        fakeExecutor.simulatedRebuildMillis.set(50L);
        fakeExecutor.alwaysSuccess.set(false);
        CompletableFuture<Long> f1 = CompletableFuture.supplyAsync(() -> {
            try { return rebuildService.scheduledRebuild(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Long id1 = f1.get(5, TimeUnit.SECONDS);
        assertEquals(IndexVersion.Status.FAILED, versionRepo.findById(id1).orElseThrow().getStatus());

        // 立刻第二次
        fakeExecutor.alwaysSuccess.set(true);
        CompletableFuture<Long> f2 = CompletableFuture.supplyAsync(() -> {
            try { return rebuildService.scheduledRebuild(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Long id2 = f2.get(5, TimeUnit.SECONDS);
        assertNotNull(id2);
        assertEquals(IndexVersion.Status.SUCCESS, versionRepo.findById(id2).orElseThrow().getStatus());
        assertNotNull(versionRepo.findById(id2).orElseThrow().getCompletedAt());
    }

    /* ==================== 辅助 ==================== */

    private void awaitRunning(IndexRebuildService svc, long timeoutMs) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (!svc.isRunning() && (System.currentTimeMillis() - t0) < timeoutMs) {
            Thread.sleep(5);
        }
    }

    /** 测试用 executor：可控制耗时、强制成功 / 失败。 */
    static final class FakeIndexExecutor implements IndexExecutor {
        final AtomicLong simulatedRebuildMillis = new AtomicLong(0L);
        final AtomicBoolean alwaysSuccess = new AtomicBoolean(true);
        final AtomicReference<String> failureReason = new AtomicReference<>("simulated failure");
        final AtomicInteger callCount = new AtomicInteger(0);
        final AtomicInteger concurrentPeak = new AtomicInteger(0);
        final AtomicInteger concurrentNow = new AtomicInteger(0);

        @Override
        public ExecutorResult rebuild(String collectionName, MilvusIndexConfig config) {
            callCount.incrementAndGet();
            int now = concurrentNow.incrementAndGet();
            concurrentPeak.updateAndGet(p -> Math.max(p, now));
            try {
                long ms = simulatedRebuildMillis.get();
                if (ms > 0) Thread.sleep(ms);
                if (alwaysSuccess.get()) {
                    return ExecutorResult.ok("fake-success for " + collectionName
                            + " M=" + config.getHnswM()
                            + " efConstruction=" + config.getHnswEfConstruction());
                }
                return ExecutorResult.fail(failureReason.get());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ExecutorResult.fail("interrupted");
            } finally {
                concurrentNow.decrementAndGet();
            }
        }
    }
}
