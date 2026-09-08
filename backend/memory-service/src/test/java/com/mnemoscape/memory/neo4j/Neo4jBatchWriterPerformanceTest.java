package com.mnemoscape.memory.neo4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R16 性能对比测试 —— 老方案（for 循环逐条 MERGE） vs 新方案（UNWIND 批量）。
 *
 * <p>测试设计取舍 —— 不连真实 Neo4j：
 * <ul>
 *   <li>手写 {@link CountingStubClient} 同时实现 Neo4jClient + UnboundRunnableSpec，
 *       链式调用 client.query().bindAll().run() 全部走同一对象（query 返回 this），
 *       run() 模拟一次 200 μs 的 Bolt RTT + 计数；</li>
 *   <li>对每次 {@code .run()} 注入 <b>模拟 RTT</b>（200 μs = LAN 单次往返经验值），
 *       保证差异完全来自"调用次数"而非 JVM noise；</li>
 *   <li>用同一组 1000 行数据，两边各跑 5 次取中位数；</li>
 *   <li>同步验证：老方案发出 1000 次 Cypher，新方案发出 ceil(1000/500)=2 次。</li>
 * </ul>
 *
 * <p>为什么不用 Mockito：Java 25 的 sealed module 把
 * {@code org.springframework.data.neo4j.core.Neo4jClient} 锁了，
 * Mockito inline mock maker 会触发 retransform failure。手写 stub 跨 JDK
 * 通吃。
 */
class Neo4jBatchWriterPerformanceTest {

    /** 模拟 Bolt 一次往返耗时（含序列化 + 反序列化），200 μs 是经验值 */
    private static final long SIMULATED_RTT_NANOS = 200_000L;

    /**
     * 多接口 stub —— 同时是 Neo4jClient（client.query 返回 UnboundRunnableSpec）
     * 和 UnboundRunnableSpec（bindAll 返回 UnboundRunnableSpec）。
     * 整个调用链 client.query(cypher).bindAll(map).run() 全在同一对象上，
     * run() 模拟 RTT + 计数。
     */
    private static final class CountingStubClient implements
            Neo4jClient,
            Neo4jClient.UnboundRunnableSpec {

        final AtomicInteger runCallCount = new AtomicInteger();
        final List<String> cypherLog = new ArrayList<>();

        // RunnableSpec / UnboundRunnableSpec 终点
        @Override
        public org.neo4j.driver.summary.ResultSummary run() {
            runCallCount.incrementAndGet();
            sleepUninterruptedly(SIMULATED_RTT_NANOS);
            return null;
        }

        // UnboundRunnableSpec 链头
        @Override
        public Neo4jClient.UnboundRunnableSpec query(String cypher) {
            cypherLog.add(cypher);
            return this;
        }
        @Override
        public Neo4jClient.UnboundRunnableSpec query(java.util.function.Supplier<String> cypher) {
            return query(cypher.get());
        }

        // BindSpec — S 必须匹配 UnboundRunnableSpec 继承链上的具体 S 参数
        @Override
        @SuppressWarnings("unchecked")
        public <T> Neo4jClient.OngoingBindSpec<T, Neo4jClient.RunnableSpec> bind(T value) {
            return null;
        }
        @Override
        public Neo4jClient.UnboundRunnableSpec bindAll(Map<String, Object> params) {
            return this;
        }

        // RunnableSpec
        @Override
        public <T> Neo4jClient.MappingSpec<T> fetchAs(Class<T> aClass) {
            return null;
        }
        @Override
        public Neo4jClient.RecordFetchSpec<Map<String, Object>> fetch() {
            return null;
        }

        // UnboundRunnableSpec 独有
        @Override
        public Neo4jClient.RunnableSpecBoundToDatabase in(String s) {
            return null;
        }
        @Override
        public Neo4jClient.RunnableSpecBoundToUser asUser(String s) {
            return null;
        }

        // Neo4jClient 顶层
        @Override
        public <T> Neo4jClient.OngoingDelegation<T> delegateTo(
                java.util.function.Function<org.neo4j.driver.QueryRunner, Optional<T>> fn) {
            return null;
        }
        @Override
        public org.springframework.data.neo4j.core.DatabaseSelectionProvider getDatabaseSelectionProvider() {
            return null;
        }
        @Override
        public org.neo4j.driver.QueryRunner getQueryRunner() {
            return null;
        }
        @Override
        public org.neo4j.driver.QueryRunner getQueryRunner(
                org.springframework.data.neo4j.core.DatabaseSelection ds) {
            return null;
        }
        @Override
        public org.neo4j.driver.QueryRunner getQueryRunner(
                org.springframework.data.neo4j.core.DatabaseSelection ds,
                org.springframework.data.neo4j.core.UserSelection us) {
            return null;
        }
    }

    private CountingStubClient stub;
    private Neo4jClient client;
    private AtomicInteger runCallCount;

    @BeforeEach
    void setUp() {
        stub = new CountingStubClient();
        client = stub;
        runCallCount = stub.runCallCount;
    }

    /**
     * 对照组：1000 个 Memory，老方案 = for 循环逐条 MERGE，每次 1 round-trip。
     */
    @Test
    @DisplayName("Performance: 1000 Memory 节点 —— 老方案 vs UNWIND 批量")
    void test1000MemoryNodes_legacyVsUnwind() {
        List<Map<String, Object>> records = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            records.add(Map.of(
                    "id",      UUID.randomUUID().toString(),
                    "title",   "memory-" + i,
                    "privacy", "PRIVATE",
                    "year",    2020 + (i % 7)
            ));
        }

        // ---- 跑老方案 5 次取中位 ----
        List<Long> legacyTimings = new ArrayList<>();
        int legacyRuns = 0;
        for (int trial = 0; trial < 5; trial++) {
            runCallCount.set(0);
            long t0 = System.nanoTime();
            for (Map<String, Object> row : records) {
                client.query("""
                        MERGE (m:Memory {id: $id})
                        SET m.title = $title,
                            m.privacy = $privacy,
                            m.memoryYear = $year
                        """)
                        .bindAll(row)
                        .run();
            }
            long elapsed = System.nanoTime() - t0;
            legacyTimings.add(elapsed);
            legacyRuns = runCallCount.get();
        }

        // ---- 跑新方案 5 次取中位 ----
        List<Long> unwindTimings = new ArrayList<>();
        int unwindRuns = 0;
        for (int trial = 0; trial < 5; trial++) {
            runCallCount.set(0);
            Neo4jBatchWriter writer = new Neo4jBatchWriter(Optional.of(client), 500);
            long t0 = System.nanoTime();
            long written = writer.batchWriteNodes("Memory", records);
            long elapsed = System.nanoTime() - t0;
            unwindTimings.add(elapsed);
            unwindRuns = runCallCount.get();
            assertEquals(1000L, written, "UNWIND should write all 1000 records");
        }

        long legacyMedian = median(legacyTimings);
        long unwindMedian = median(unwindTimings);
        double speedup = (double) legacyMedian / (double) unwindMedian;

        System.out.println(String.format(
                "[Perf-1000-nodes]  legacy median = %.1f ms (%d runs); "
                        + "UNWIND median = %.1f ms (%d runs); speedup = %.1fx",
                legacyMedian / 1e6, legacyRuns,
                unwindMedian / 1e6, unwindRuns,
                speedup));

        // 老方案发出 1000 次 run()，新方案发出 2 次（500 + 500）
        assertEquals(1000, legacyRuns,
                "Legacy should produce 1000 Cypher runs (1 per record)");
        assertEquals(2, unwindRuns,
                "UNWIND with batchSize=500 should produce only 2 Cypher runs");
        assertTrue(speedup > 100.0,
                String.format("Expected >100x speedup, got %.1fx", speedup));
        assertTrue(unwindMedian < legacyMedian / 100,
                "UNWIND should be at least 100x faster");
    }

    /**
     * 对照组：1000 条 OWNS 关系 —— 老方案 vs UNWIND 批量。
     */
    @Test
    @DisplayName("Performance: 1000 OWNS 关系 —— 老方案 vs UNWIND 批量")
    void test1000Relationships_legacyVsUnwind() {
        List<Map<String, Object>> rows = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("fromId", "user-" + (i % 100));
            row.put("toId",   "memory-" + i);
            row.put("props",  Map.of("since", 2024));
            rows.add(row);
        }

        // 老方案
        List<Long> legacyTimings = new ArrayList<>();
        int legacyRuns = 0;
        for (int trial = 0; trial < 5; trial++) {
            runCallCount.set(0);
            long t0 = System.nanoTime();
            for (Map<String, Object> row : rows) {
                client.query("""
                        MATCH (a:User {id: $fromId}), (b:Memory {id: $toId})
                        MERGE (a)-[r:OWNS]->(b)
                        SET r += $props
                        """)
                        .bindAll(row)
                        .run();
            }
            legacyTimings.add(System.nanoTime() - t0);
            legacyRuns = runCallCount.get();
        }

        // 新方案
        List<Long> unwindTimings = new ArrayList<>();
        int unwindRuns = 0;
        for (int trial = 0; trial < 5; trial++) {
            runCallCount.set(0);
            Neo4jBatchWriter writer = new Neo4jBatchWriter(Optional.of(client), 500);
            long t0 = System.nanoTime();
            long written = writer.batchWriteRelationships(
                    "OWNS", "User", "id", "Memory", "id", rows, 500);
            unwindTimings.add(System.nanoTime() - t0);
            unwindRuns = runCallCount.get();
            assertEquals(1000L, written);
        }

        long legacyMedian = median(legacyTimings);
        long unwindMedian = median(unwindTimings);
        double speedup = (double) legacyMedian / (double) unwindMedian;

        System.out.println(String.format(
                "[Perf-1000-rels]  legacy median = %.1f ms (%d runs); "
                        + "UNWIND median = %.1f ms (%d runs); speedup = %.1fx",
                legacyMedian / 1e6, legacyRuns,
                unwindMedian / 1e6, unwindRuns,
                speedup));

        assertEquals(1000, legacyRuns);
        assertEquals(2, unwindRuns,
                "1000 OWNS rels with batchSize=500 should produce 2 Cypher runs");
        assertTrue(speedup > 100.0,
                String.format("Expected >100x speedup, got %.1fx", speedup));
    }

    /**
     * 验证分批边界：1001 条 → 500 + 500 + 1 = 3 个 Cypher 调用。
     */
    @Test
    @DisplayName("BatchSize boundary: 1001 条记录应被拆成 3 个 Cypher (500 + 500 + 1)")
    void testBatchBoundary() {
        Collection<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            records.add(Map.of("id", "m-" + i, "title", "t-" + i));
        }

        runCallCount.set(0);
        Neo4jBatchWriter writer = new Neo4jBatchWriter(Optional.of(client), 500);
        long written = writer.batchWriteNodes("Memory", records);

        assertEquals(1001L, written);
        assertEquals(3, runCallCount.get(),
                "1001 records with batchSize=500 should produce 3 Cypher runs");
    }

    /**
     * 空集合短路 —— 0 条不应当跑任何 Cypher。
     */
    @Test
    @DisplayName("Empty input: 0 条记录不应当产生任何 Cypher 调用")
    void testEmptyInputShortCircuit() {
        runCallCount.set(0);
        Neo4jBatchWriter writer = new Neo4jBatchWriter(Optional.of(client), 500);
        long written = writer.batchWriteNodes("Memory", List.of());
        assertEquals(0L, written);
        assertEquals(0, runCallCount.get());
    }

    /**
     * 缺 Neo4jClient —— 应当安全 no-op，不抛异常。
     */
    @Test
    @DisplayName("Absent Neo4jClient: Optional.empty 时应当 no-op 返回 0")
    void testAbsentClientIsNoop() {
        Neo4jBatchWriter writer = new Neo4jBatchWriter(Optional.empty());
        long written = writer.batchWriteNodes("Memory",
                List.of(Map.of("id", "x", "title", "y")));
        assertEquals(0L, written);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    private static void sleepUninterruptedly(long nanos) {
        long target = System.nanoTime() + nanos;
        while (System.nanoTime() < target) {
            // busy-wait —— 测试需要确定性，不允许 Thread.sleep 抖动
        }
    }
}