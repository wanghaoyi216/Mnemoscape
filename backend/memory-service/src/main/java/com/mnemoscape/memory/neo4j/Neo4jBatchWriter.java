package com.mnemoscape.memory.neo4j;

import com.mnemoscape.memory.metrics.Neo4jWriteMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R16：通用 Neo4j 批量写入器 —— 全部基于 UNWIND + 一次 round-trip。
 *
 * <p>原 {@link com.mnemoscape.memory.service.MemoryGraphService} 的写入路径
 * 是 {@code for (Memory m : list) mergeMemoryNode(m)}，每条 Memory 至少产生
 * 1 个 MERGE（节点） + 1 个 MERGE（User） + 4 个 UNWIND（实体），N 条记忆
 * 就是 6N 次 Cypher → 6N 次 RTT，序列化 / 反序列化 / 锁开销线性放大。
 *
 * <p>本类把所有写都改成 <b>一次 Cypher 一批</b>：
 * <pre>
 *   UNWIND $rows AS row
 *   MERGE (n:Label {id: row.id})
 *   SET n += row.props
 * </pre>
 * 默认批大小 500（NEO4J 社区版官方推荐区间：100–1000，500 是 Cypher 计划
 * 缓存命中率 / heap 占用 / 单事务回滚代价的折中点）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>批内单事务</b>：500 条一组跑一个 session.run()，Neo4j 自动包成
 *       一个事务。失败 → 该批回滚，不污染已写入批次。</li>
 *   <li><b>批间独立事务</b>：批与批之间不跨事务，方便在长链路里重试 /
 *       跳过某批。失败的可观测性比"全有或全无"好得多。</li>
 *   <li><b>空集短路</b>：0 条直接返回 0，不再跑 Cypher。</li>
 *   <li><b>mapProp 增量合并</b>：除了 id 这种"键字段"，其他列（title /
 *       privacy / year…）都走 {@code SET n += row.props}，避免一改 schema
 *       就改 Cypher 字符串。</li>
 * </ul>
 *
 * <p>所有方法异常一律吞掉 + 打 WARN，跟 {@code MemoryGraphService} 一样：
 * 图谱是派生数据，挂掉不应阻塞主写库流程。
 */
@Component
public class Neo4jBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(Neo4jBatchWriter.class);

    /** 官方推荐区间 100–1000，500 是社区默认值。 */
    public static final int DEFAULT_BATCH_SIZE = 500;

    private final Optional<Neo4jClient> neo4jClient;
    private final int defaultBatchSize;
    private final Neo4jWriteMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public Neo4jBatchWriter(Optional<Neo4jClient> neo4jClient,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) Neo4jWriteMetrics metrics) {
        this(neo4jClient, DEFAULT_BATCH_SIZE, metrics);
    }

    public Neo4jBatchWriter(Optional<Neo4jClient> neo4jClient) {
        this(neo4jClient, DEFAULT_BATCH_SIZE, null);
    }

    public Neo4jBatchWriter(Optional<Neo4jClient> neo4jClient, int defaultBatchSize) {
        this(neo4jClient, defaultBatchSize, null);
    }

    /**
     * Full constructor — wired by Spring in production.  The
     * {@link Neo4jWriteMetrics} dependency is intentionally non-required
     * (defaults to {@code null}) so unit tests that wire this class by hand
     * without a Spring context keep working.
     */
    public Neo4jBatchWriter(Optional<Neo4jClient> neo4jClient,
                            int defaultBatchSize,
                            Neo4jWriteMetrics metrics) {
        this.neo4jClient = neo4jClient;
        this.defaultBatchSize = defaultBatchSize;
        this.metrics = metrics;
    }

    /**
     * 通用节点批量 MERGE —— 一次 Cypher 写整批节点。
     *
     * @param label       节点标签（如 "Memory" / "User"）
     * @param idField     用作 MERGE 键的字段名（默认 "id"）
     * @param records     待写入记录；每条记录的 {@code idField} 必须是唯一键，
     *                    其余字段会被 {@code SET n += row} 增量合并
     * @param batchSize   单批大小（≤0 走 {@link #DEFAULT_BATCH_SIZE}）
     * @return 实际写入（MERGE 命中 = 新建 + 匹配）的总条数
     */
    public long batchWriteNodes(String label,
                                String idField,
                                Collection<? extends Map<String, Object>> records,
                                int batchSize) {
        if (neo4jClient.isEmpty()) return 0L;
        if (records == null || records.isEmpty()) return 0L;
        if (label == null || label.isBlank()) {
            log.warn("batchWriteNodes: empty label, skip {} records", records.size());
            return 0L;
        }
        int effectiveBatch = batchSize <= 0 ? defaultBatchSize : batchSize;
        Neo4jClient client = neo4jClient.get();

        // 关键 Cypher —— UNWIND + MERGE + SET n += row 一次性写整批
        // 不在 Cypher 里直接拼 label —— 用字符串模板预编译一次，避免运行时反射
        String cypher = """
                UNWIND $rows AS row
                MERGE (n:%s {%s: row.%s})
                SET n += row
                """.formatted(label, idField, idField);

        long total = 0L;
        List<? extends Map<String, Object>> snapshot = new ArrayList<>(records);
        for (int from = 0; from < snapshot.size(); from += effectiveBatch) {
            int to = Math.min(from + effectiveBatch, snapshot.size());
            List<Map<String, Object>> chunk = new ArrayList<>(snapshot.subList(from, to));
            long batchStart = System.nanoTime();
            boolean ok = false;
            try {
                client.query(cypher)
                        .bindAll(Map.of("rows", chunk))
                        .run();
                total += chunk.size();
                ok = true;
                log.debug("UNWIND batch wrote {} {} nodes [{}..{})",
                        chunk.size(), label, from, to);
            } catch (Exception e) {
                // 批失败只丢这一批，不影响已写入的；主写库流程不退化
                log.warn("UNWIND batch FAILED for {} nodes [{}..{}): {}",
                        label, from, to, e.toString());
            } finally {
                if (metrics != null) {
                    metrics.record(Neo4jWriteMetrics.OP_NODES, chunk.size(), ok, batchStart);
                }
            }
        }
        return total;
    }

    /**
     * 重载：默认 idField="id" + 默认 batchSize。
     */
    public long batchWriteNodes(String label,
                                Collection<? extends Map<String, Object>> records) {
        return batchWriteNodes(label, "id", records, defaultBatchSize);
    }

    /**
     * 通用关系批量 MERGE —— 通过两端节点 id 匹配后建立边。
     *
     * @param relType     关系类型（如 "OWNS" / "MENTIONS"）
     * @param fromLabel   起点标签
     * @param fromIdField 起点 id 字段名
     * @param toLabel     终点标签
     * @param toIdField   终点 id 字段名
     * @param rows        每条记录必须含 {@code fromId} + {@code toId}（键名固定），
     *                    其余字段会 SET 到关系属性
     */
    public long batchWriteRelationships(String relType,
                                        String fromLabel,
                                        String fromIdField,
                                        String toLabel,
                                        String toIdField,
                                        Collection<? extends Map<String, Object>> rows,
                                        int batchSize) {
        if (neo4jClient.isEmpty()) return 0L;
        if (rows == null || rows.isEmpty()) return 0L;
        int effectiveBatch = batchSize <= 0 ? defaultBatchSize : batchSize;
        Neo4jClient client = neo4jClient.get();

        String cypher = """
                UNWIND $rows AS row
                MATCH (a:%s {%s: row.fromId}), (b:%s {%s: row.toId})
                MERGE (a)-[r:%s]->(b)
                SET r += row.props
                """.formatted(fromLabel, fromIdField,
                              toLabel, toIdField,
                              relType);

        long total = 0L;
        List<? extends Map<String, Object>> snapshot = new ArrayList<>(rows);
        for (int from = 0; from < snapshot.size(); from += effectiveBatch) {
            int to = Math.min(from + effectiveBatch, snapshot.size());
            List<Map<String, Object>> chunk = new ArrayList<>(snapshot.subList(from, to));
            long batchStart = System.nanoTime();
            boolean ok = false;
            try {
                client.query(cypher)
                        .bindAll(Map.of("rows", chunk))
                        .run();
                total += chunk.size();
                ok = true;
            } catch (Exception e) {
                log.warn("UNWIND rel batch FAILED for {} [{}..{}): {}",
                        relType, from, to, e.toString());
            } finally {
                if (metrics != null) {
                    metrics.record(Neo4jWriteMetrics.OP_RELS, chunk.size(), ok, batchStart);
                }
            }
        }
        return total;
    }

    /** 重载：默认 batchSize。 */
    public long batchWriteRelationships(String relType,
                                        String fromLabel,
                                        String toLabel,
                                        Collection<? extends Map<String, Object>> rows) {
        return batchWriteRelationships(relType, fromLabel, "id", toLabel, "id", rows, defaultBatchSize);
    }

    /**
     * 一次性把 N 个 User 节点批写入（id 字段固定 "id"）。
     * 抽出便捷方法，让 {@code MemoryGraphService.writeMemoryGraphBatch}
     * 调用起来一行就够，不用每次拼 Map。
     */
    public long batchWriteUsers(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return 0L;
        List<Map<String, Object>> rows = new ArrayList<>(userIds.size());
        for (String uid : userIds) {
            rows.add(Map.of("id", uid));
        }
        return batchWriteNodes("User", rows);
    }

    /**
     * 写入度量 —— 方便上层记录监控 / 日志。
     */
    public static final class WriteStats {
        public final long nodesWritten;
        public final long relsWritten;
        public final long elapsedMillis;

        public WriteStats(long nodesWritten, long relsWritten, long elapsedMillis) {
            this.nodesWritten = nodesWritten;
            this.relsWritten = relsWritten;
            this.elapsedMillis = elapsedMillis;
        }

        @Override
        public String toString() {
            return "WriteStats{nodes=" + nodesWritten
                    + ", rels=" + relsWritten
                    + ", elapsedMs=" + elapsedMillis + "}";
        }
    }

    /**
     * 高层入口：一次调用把一批记忆 + 它们的 OWNS + 实体 + 边全部写入。
     * 上层（异步增强链路）只需要传 records，writer 负责拆分 / 重试 / 吞异常。
     *
     * @param userIdOfMemory map<memoryId, userId> —— 批量从源头提取，避免
     *                       再回查 MySQL
     * @param entityRowsByRelType 形如：
     *        { "MENTIONS"   -> [{memoryId, name, label:"Person"}, ...],
     *          "HAPPENED_IN"-> [{memoryId, name, label:"Location"}, ...],
     *          "CONTAINS"   -> [{memoryId, name, label:"Object"}, ...],
     *          "FEELS"      -> [{memoryId, name, label:"Emotion"}, ...] }
     *        同一 memoryId 的所有 name 会先 MERGE 成实体节点，再建边。
     */
    public WriteStats writeMemoryGraphBatch(
            Collection<? extends Map<String, Object>> memoryRows,
            Map<String, String> userIdOfMemory,
            Map<String, List<Map<String, Object>>> entityRowsByRelType) {

        long t0 = System.nanoTime();
        if (neo4jClient.isEmpty()) {
            return new WriteStats(0, 0, 0);
        }

        long nodeCount = 0L;
        long relCount = 0L;

        // 1) Memory 节点
        nodeCount += batchWriteNodes("Memory", memoryRows);

        // 2) User 节点
        nodeCount += batchWriteUsers(userIdOfMemory.values());

        // 3) OWNS 边
        List<Map<String, Object>> ownsRows = new ArrayList<>();
        for (Map.Entry<String, String> e : userIdOfMemory.entrySet()) {
            ownsRows.add(Map.of(
                    "fromId", e.getValue(),
                    "toId",   e.getKey(),
                    "props",  Map.of()
            ));
        }
        relCount += batchWriteRelationships(
                "OWNS", "User", "Memory", ownsRows);

        // 4) 实体节点（按 label 去重）
        if (entityRowsByRelType != null) {
            for (List<Map<String, Object>> rows : entityRowsByRelType.values()) {
                HashMap<String, HashMap<String, Map<String, Object>>> byLabel = new HashMap<>();
                for (Map<String, Object> r : rows) {
                    String label = String.valueOf(r.getOrDefault("label", "Entity"));
                    HashMap<String, Map<String, Object>> bucket =
                            byLabel.computeIfAbsent(label, k -> new HashMap<>());
                    bucket.put(String.valueOf(r.get("name")), r);
                }
                for (String label : byLabel.keySet()) {
                    HashMap<String, Map<String, Object>> bucket = byLabel.get(label);
                    List<Map<String, Object>> nodeRows = new ArrayList<>();
                    for (Map<String, Object> r : bucket.values()) {
                        Map<String, Object> node = new HashMap<>(r);
                        node.put("id", r.get("name"));  // 实体 key 用 name
                        nodeRows.add(node);
                    }
                    nodeCount += batchWriteNodes(label, "id", nodeRows, defaultBatchSize);
                }
            }

            // 5) 实体关系
            AtomicLong rel = new AtomicLong(0);
            entityRowsByRelType.forEach((relType, rows) -> {
                List<Map<String, Object>> relRows = new ArrayList<>(rows.size());
                for (Map<String, Object> r : rows) {
                    relRows.add(Map.of(
                            "fromId", r.get("memoryId"),
                            "toId",   r.get("name"),
                            "props",  Map.of()
                    ));
                }
                rel.addAndGet(batchWriteRelationships(relType, "Memory", "id",
                        inferLabelForRel(relType), "id", relRows, defaultBatchSize));
            });
            relCount += rel.get();
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        return new WriteStats(nodeCount, relCount, elapsedMs);
    }

    private static String inferLabelForRel(String relType) {
        return switch (relType) {
            case "MENTIONS"    -> "Person";
            case "HAPPENED_IN" -> "Location";
            case "CONTAINS"    -> "Object";
            case "FEELS"       -> "Emotion";
            default            -> "Entity";
        };
    }
}