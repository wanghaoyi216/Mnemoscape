package com.mnemoscape.memory.neo4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R16 AsyncIndexUpdater 单元测试 —— 手写 stub 替代 Mockito（Java 25 sealed
 * module 限制 inline byte-buddy 重写 Neo4jClient 接口）。
 *
 * <p>验证三种核心行为：
 * <ul>
 *   <li>索引全 ONLINE → 只跑 SHOW INDEXES，不调 awaitIndexesOnline()</li>
 *   <li>有非 ONLINE 索引 → 调 awaitIndexesOnline($timeout)</li>
 *   <li>Neo4jClient 缺位 → 安全 no-op</li>
 * </ul>
 */
class AsyncIndexUpdaterTest {

    /**
     * 同 Neo4jBatchWriterPerformanceTest 的 stub —— 多接口对象，
     * 用计数器记录每次 query / fetch / run 调用。
     */
    private static final class StubClient implements
            Neo4jClient,
            Neo4jClient.UnboundRunnableSpec {

        // 返回 SHOW INDEXES 用的 fetch 链路
        Collection<Map<String, Object>> fetchResult = List.of();
        // 用来记录 bindAll 时塞进 timeout 的值
        Map<String, Object> lastBound = new HashMap<>();
        // 计数器
        final AtomicInteger runCount = new AtomicInteger();
        final AtomicInteger fetchCount = new AtomicInteger();

        @Override
        public org.neo4j.driver.summary.ResultSummary run() {
            runCount.incrementAndGet();
            return null;
        }
        @Override
        public Neo4jClient.UnboundRunnableSpec query(String cypher) {
            return this;
        }
        @Override
        public Neo4jClient.UnboundRunnableSpec query(java.util.function.Supplier<String> cypher) {
            return query(cypher.get());
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> Neo4jClient.OngoingBindSpec<T, Neo4jClient.RunnableSpec> bind(T value) { return null; }
        @Override
        public Neo4jClient.UnboundRunnableSpec bindAll(Map<String, Object> params) {
            lastBound.putAll(params);
            return this;
        }
        @Override
        public <T> Neo4jClient.MappingSpec<T> fetchAs(Class<T> aClass) { return null; }
        @Override
        public Neo4jClient.RecordFetchSpec<Map<String, Object>> fetch() {
            fetchCount.incrementAndGet();
            return new StubRecordFetch(fetchResult);
        }
        @Override
        public Neo4jClient.RunnableSpecBoundToDatabase in(String s) { return null; }
        @Override
        public Neo4jClient.RunnableSpecBoundToUser asUser(String s) { return null; }
        @Override
        public <T> Neo4jClient.OngoingDelegation<T> delegateTo(
                java.util.function.Function<org.neo4j.driver.QueryRunner, Optional<T>> fn) { return null; }
        @Override
        public org.springframework.data.neo4j.core.DatabaseSelectionProvider getDatabaseSelectionProvider() { return null; }
        @Override
        public org.neo4j.driver.QueryRunner getQueryRunner() { return null; }
        @Override
        public org.neo4j.driver.QueryRunner getQueryRunner(
                org.springframework.data.neo4j.core.DatabaseSelection ds) { return null; }
        @Override
        public org.neo4j.driver.QueryRunner getQueryRunner(
                org.springframework.data.neo4j.core.DatabaseSelection ds,
                org.springframework.data.neo4j.core.UserSelection us) { return null; }
    }

    /**
     * RecordFetchSpec 的最小实现 —— fetch().all() 直接返回构造时塞的列表。
     */
    private static final class StubRecordFetch implements
            Neo4jClient.RecordFetchSpec<Map<String, Object>> {
        private final Collection<Map<String, Object>> data;

        StubRecordFetch(Collection<Map<String, Object>> data) {
            this.data = data;
        }
        @Override public Optional<Map<String, Object>> one() { return data.stream().findFirst(); }
        @Override public Optional<Map<String, Object>> first() { return one(); }
        @Override public Collection<Map<String, Object>> all() { return data; }
    }

    private StubClient stub;
    private Neo4jClient client;

    @BeforeEach
    void setUp() {
        stub = new StubClient();
        client = stub;
    }

    @Test
    @DisplayName("1) 索引全 ONLINE 时不应当调用 awaitIndexesOnline，只跑 SHOW INDEXES")
    void testAllOnlineSkipsAwait() {
        // SHOW INDEXES 返回两条全部 ONLINE
        stub.fetchResult = List.of(
                Map.of("name", "idx_memory_id", "state", "ONLINE", "type", "BTREE"),
                Map.of("name", "idx_user_id",    "state", "ONLINE", "type", "BTREE")
        );

        AsyncIndexUpdater updater = new AsyncIndexUpdater(Optional.of(client));
        updater.awaitIndexesOnline();

        assertEquals(1, stub.fetchCount.get(),
                "should probe index state via SHOW INDEXES once");
        assertEquals(0, stub.runCount.get(),
                "should NOT call db.awaitIndexesOnline when all indexes ONLINE");
        // syncCount 仍然计数（探测也是一次同步检查）
        assertEquals(1L, updater.getSyncCount());
    }

    @Test
    @DisplayName("2) 存在非 ONLINE 索引时应当触发 db.awaitIndexesOnline")
    void testAwaitIndexesOnlineWhenPending() {
        stub.fetchResult = List.of(
                Map.of("name", "idx_vector", "state", "POPULATING", "type", "VECTOR")
        );

        AsyncIndexUpdater updater = new AsyncIndexUpdater(Optional.of(client));
        updater.awaitIndexesOnline();

        assertEquals(1, stub.fetchCount.get());
        assertEquals(1, stub.runCount.get(),
                "should call db.awaitIndexesOnline when any index not ONLINE");
        assertEquals(1L, updater.getSyncCount());
        // 默认方法的 '10s' 是 Cypher 字符串字面量，不走 bindAll
        // （参考测试 4 的自定义 timeout 走参数绑定）
    }

    @Test
    @DisplayName("3) Neo4jClient 缺位时不应抛异常，syncCount 不应增长")
    void testAbsentClientIsNoop() {
        AsyncIndexUpdater updater = new AsyncIndexUpdater(Optional.empty());
        updater.awaitIndexesOnline();
        assertEquals(0L, updater.getSyncCount());
        assertEquals(0, stub.fetchCount.get());
        assertEquals(0, stub.runCount.get());
    }

    @Test
    @DisplayName("4) 自定义 timeout 应被绑定到 $timeout 参数")
    void testCustomTimeoutBinding() {
        AsyncIndexUpdater updater = new AsyncIndexUpdater(Optional.of(client));
        updater.awaitIndexesOnline("30s");

        assertEquals("30s", stub.lastBound.get("timeout"),
                "custom timeout should be bound to $timeout");
        assertEquals(1, stub.runCount.get());
        assertTrue(updater.getSyncCount() >= 1);
    }
}