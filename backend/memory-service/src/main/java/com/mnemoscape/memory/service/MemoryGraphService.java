package com.mnemoscape.memory.service;

import com.mnemoscape.memory.model.dto.EntityExtractionResponse;
import com.mnemoscape.memory.model.entity.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

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

    /**
     * Neo4jClient 通过 Optional 注入 —— 当 spring-boot-starter-data-neo4j
     * 在 classpath 上但 Neo4j 服务不可达 / 未配置 password 时，依旧可启动；
     * 调用 {@link #writeMemoryGraph} 时只是静默跳过。
     */
    public MemoryGraphService(Optional<Neo4jClient> neo4jClient) {
        this.neo4jClient = neo4jClient;
        if (neo4jClient.isEmpty()) {
            log.warn("MemoryGraphService: Neo4jClient not injected; graph writes will be no-ops");
        }
    }

    /**
     * 把 memory + 抽取出来的实体写入图谱。任何步骤异常 → 全局 WARN，绝不外抛。
     */
    public void writeMemoryGraph(Memory memory, EntityExtractionResponse entities) {
        if (neo4jClient.isEmpty()) return;
        if (memory == null || memory.getId() == null) return;
        Neo4jClient client = neo4jClient.get();

        try {
            mergeMemoryNode(client, memory);
            linkUser(client, memory);
            if (entities == null) return;
            linkEntities(client, memory.getId(), "Person",   "MENTIONS",     entities.getPeople());
            linkEntities(client, memory.getId(), "Location", "HAPPENED_IN",  entities.getLocations());
            linkEntities(client, memory.getId(), "Object",   "CONTAINS",     entities.getObjects());
            linkEntities(client, memory.getId(), "Emotion",  "FEELS",        entities.getEmotionTags());
        } catch (Exception e) {
            // 任何 Cypher / 网络 / 配置错误：吞掉，主流程不退化
            log.warn("Skipped Neo4j graph write for memory {} due to {}: {}",
                    memory.getId(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

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

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
