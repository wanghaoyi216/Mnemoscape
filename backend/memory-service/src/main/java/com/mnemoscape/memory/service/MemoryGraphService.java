package com.mnemoscape.memory.service;

import com.mnemoscape.memory.model.dto.EntityExtractionResponse;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.neo4j.AsyncIndexUpdater;
import com.mnemoscape.memory.neo4j.Neo4jBatchWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把一段 {@link Memory} 与它的 {@link EntityExtractionResponse} 投射到 Neo4j。
 *
 * <p>图模型（所有 MERGE 一律幂等）：
 * <pre>
 *   (:User {id})           -[:OWNS]->        (:Memory {id, title, year, privacy})
 *   (:Memory)              -[:MENTIONS]->    (:Person   {name})
 *   (:Memory)              -[:HAPPENED_IN]-> (:Location {name})
 *   (:Memory)              -[:CONTAINS]->    (:Object   {name})
 *   (:Memory)              -[:FEELS]->       (:Emotion  {name})
 * </pre>
 *
 * <p>R16 起，单条写路径仍保留（向后兼容 + 单条写延迟更低），但 <b>推荐</b>
 * 走 {@link #writeMemoryGraphBatch} —— 用 {@link Neo4jBatchWriter} 的
 * UNWIND 一次 round-trip 写完一批；写完后异步触发
 * {@link AsyncIndexUpdater#awaitIndexesOnline()}。
 *
 * <p>所有方法都是 best-effort —— 任何 Neo4j 故障都被吞掉并打 WARN，不能让
 * 主写库流程因为图谱挂掉而失败。理由：图谱是"派生数据"，丢失了下一次
 * 再写入即可，强一致没有商业必要。
 *
 * <p>使用 {@link Neo4jClient} 而不是 Spring Data Neo4j 的 Repository / OGM ——
 * MERGE 语句 + 参数绑定的细粒度控制更直接，避免给 Memory 实体加 OGM
 * 注解污染 JPA 的同一个类。
 */
@Service
public class MemoryGraphService {

    private static final Logger log = LoggerFactory.getLogger(MemoryGraphService.class);

    private final Optional<Neo4jClient> neo4jClient;
    private final Neo4jBatchWriter batchWriter;
    private final AsyncIndexUpdater indexUpdater;

    /**
     * Neo4jClient 通过 Optional 注入 —— 当 spring-boot-starter-data-neo4j
     * 在 classpath 上但 Neo4j 服务不可达 / 未配置 password 时，依旧可启动；
     * 调用 {@link #writeMemoryGraph} 时只是静默跳过。
     */
    public MemoryGraphService(Optional<Neo4jClient> neo4jClient,
                              Neo4jBatchWriter batchWriter,
                              AsyncIndexUpdater indexUpdater) {
        this.neo4jClient = neo4jClient;
        this.batchWriter = batchWriter;
        this.indexUpdater = indexUpdater;
        if (neo4jClient.isEmpty()) {
            log.warn("MemoryGraphService: Neo4jClient not injected; graph writes will be no-ops");
        }
    }

    /**
     * 单条写（保留向后兼容）—— 仍然走 UNWIND 路径（writer.batchSize=1 也走 UNWIND），
     * 老调用方零迁移成本。
     */
    public void writeMemoryGraph(Memory memory, EntityExtractionResponse entities) {
        if (memory == null || memory.getId() == null) return;
        writeMemoryGraphBatch(List.of(memory),
                entities == null ? List.of() : List.of(entities));
    }

    /**
     * R16 主路径：批量写入 —— 一次 UNWIND 写完所有 Memory/User/实体节点 + 边。
     * 单批失败只丢该批；写完后异步同步索引（不阻塞调用方）。
     *
     * @param memories   待写入的记忆
     * @param entities   与 memories 等长的实体抽取结果；可为 null 表示无实体
     */
    public Neo4jBatchWriter.WriteStats writeMemoryGraphBatch(
            List<Memory> memories,
            List<EntityExtractionResponse> entities) {
        if (neo4jClient.isEmpty()) return new Neo4jBatchWriter.WriteStats(0, 0, 0);
        if (memories == null || memories.isEmpty()) {
            return new Neo4jBatchWriter.WriteStats(0, 0, 0);
        }
        // 对齐长度 —— entities 缺位按空处理
        List<EntityExtractionResponse> safeEntities =
                entities == null ? List.of() : entities;

        try {
            // 1) 构造 Memory 节点行
            List<Map<String, Object>> memoryRows = new ArrayList<>(memories.size());
            Map<String, String> userIdOfMemory = new LinkedHashMap<>();
            for (Memory m : memories) {
                if (m == null || m.getId() == null) continue;
                Map<String, Object> row = new HashMap<>();
                row.put("id",      m.getId());
                row.put("title",   nonNull(m.getTitle()));
                row.put("privacy", m.getPrivacyLevel() == null ? "PRIVATE" : m.getPrivacyLevel().name());
                row.put("year",    m.getMemoryYear() == null ? 0 : m.getMemoryYear());
                row.put("updatedAt", System.currentTimeMillis());
                memoryRows.add(row);
                if (m.getUserId() != null) {
                    userIdOfMemory.put(m.getId(), m.getUserId());
                }
            }

            // 2) 构造实体行：{relType -> [{memoryId, name, label}, ...]}
            Map<String, List<Map<String, Object>>> entityRowsByRelType = new HashMap<>();
            entityRowsByRelType.put("MENTIONS",    new ArrayList<>());
            entityRowsByRelType.put("HAPPENED_IN", new ArrayList<>());
            entityRowsByRelType.put("CONTAINS",    new ArrayList<>());
            entityRowsByRelType.put("FEELS",       new ArrayList<>());

            for (int i = 0; i < memories.size(); i++) {
                Memory m = memories.get(i);
                if (m == null || m.getId() == null) continue;
                EntityExtractionResponse ext = i < safeEntities.size() ? safeEntities.get(i) : null;
                if (ext == null) continue;
                appendEntities(entityRowsByRelType.get("MENTIONS"),    m.getId(), ext.getPeople(),     "Person");
                appendEntities(entityRowsByRelType.get("HAPPENED_IN"), m.getId(), ext.getLocations(),  "Location");
                appendEntities(entityRowsByRelType.get("CONTAINS"),    m.getId(), ext.getObjects(),    "Object");
                appendEntities(entityRowsByRelType.get("FEELS"),       m.getId(), ext.getEmotionTags(),"Emotion");
            }

            // 3) 一次性写入
            Neo4jBatchWriter.WriteStats stats =
                    batchWriter.writeMemoryGraphBatch(memoryRows, userIdOfMemory, entityRowsByRelType);

            // 4) 写完异步同步索引 —— 不阻塞主流程
            indexUpdater.awaitIndexesOnline();

            log.info("MemoryGraphBatch UNWIND write done: {}", stats);
            return stats;
        } catch (Exception e) {
            log.warn("Batch Neo4j graph write skipped due to {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return new Neo4jBatchWriter.WriteStats(0, 0, 0);
        }
    }

    private static void appendEntities(List<Map<String, Object>> sink,
                                       String memoryId,
                                       List<String> names,
                                       String label) {
        if (names == null || names.isEmpty()) return;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            sink.add(Map.of(
                    "memoryId", memoryId,
                    "name",     name,
                    "label",    label
            ));
        }
    }

    /**
     * 单条 MERGE 的"老"路径 —— 保留只是为了单条场景下的极低延迟（零 list 装箱）。
     * 批量场景请走 {@link #writeMemoryGraphBatch}。
     */
    private static void mergeMemoryNode(Neo4jClient client, Memory memory) {
        client.query("""
                MERGE (m:Memory {id: $id})
                SET m.title       = $title,
                    m.privacy     = $privacy,
                    m.memoryYear  = $year,
                    m.updatedAt   = timestamp()
                """)
            .bindAll(Map.of(
                "id",      memory.getId(),
                "title",   nonNull(memory.getTitle()),
                "privacy", memory.getPrivacyLevel() == null ? "PRIVATE" : memory.getPrivacyLevel().name(),
                "year",    memory.getMemoryYear() == null ? 0 : memory.getMemoryYear()
            ))
            .run();
    }

    private static void linkUser(Neo4jClient client, Memory memory) {
        if (memory.getUserId() == null) return;
        client.query("""
                MERGE (u:User {id: $userId})
                WITH u
                MATCH (m:Memory {id: $memId})
                MERGE (u)-[:OWNS]->(m)
                """)
            .bindAll(Map.of(
                "userId", memory.getUserId(),
                "memId",  memory.getId()
            ))
            .run();
    }

    private static void linkEntities(Neo4jClient client,
                                     String memoryId,
                                     String label,
                                     String relationship,
                                     List<String> names) {
        if (names == null || names.isEmpty()) return;
        // 单条 UNWIND — 一次 round-trip 写完整批实体 + 边
        String cypher = """
                UNWIND $names AS name
                MERGE (e:%s {name: name})
                WITH e
                MATCH (m:Memory {id: $memId})
                MERGE (m)-[r:%s]->(e)
                ON CREATE SET r.createdAt = timestamp()
                """.formatted(label, relationship);
        client.query(cypher)
            .bindAll(Map.of(
                "names", names,
                "memId", memoryId
            ))
            .run();
    }

    public Map<String, Object> getMemoryGraph(String memoryId, String userId) {
        if (neo4jClient.isEmpty()) {
            return Map.of("entities", List.of(), "sharedMemories", List.of());
        }
        Neo4jClient client = neo4jClient.get();
        try {
            // 1. 获取该记忆直接相连的所有实体 (Person, Location, Object, Emotion)
            String cypher = """
                MATCH (m:Memory {id: $memId})-[r]->(e)
                RETURN labels(e)[0] AS type, e.name AS name
                """;
            var entities = client.query(cypher)
                    .bind(memoryId).to("memId")
                    .fetch()
                    .all();

            // 2. 获取与这些实体相连的，属于同一个用户的所有其他记忆
            String shareCypher = """
                MATCH (m1:Memory {id: $memId})-[r1]->(e)<-[r2]-(m2:Memory)
                MATCH (u:User {id: $userId})-[:OWNS]->(m2)
                WHERE m1 <> m2
                RETURN DISTINCT m2.id AS id, m2.title AS title, e.name AS sharedEntity, labels(e)[0] AS entityType
                """;
            var sharedMemories = client.query(shareCypher)
                    .bindAll(Map.of("memId", memoryId, "userId", userId))
                    .fetch()
                    .all();

            return Map.of(
                "entities", entities,
                "sharedMemories", sharedMemories
            );
        } catch (Exception e) {
            log.warn("Failed to query Neo4j graph for memory {}: {}", memoryId, e.toString());
            return Map.of("entities", List.of(), "sharedMemories", List.of());
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}