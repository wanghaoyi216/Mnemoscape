package com.mnemoscape.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Real Neo4j relation lookup tool.
 *
 * <p>Cypher walks the {@code (:Memory)-[r]-(other)} subgraph for a single
 * memory id, scoped to the current user via {@code (:User {id})-[:OWNS]->(:Memory)}.
 * The graph was already populated by memory-service's {@code MemoryGraphService}.
 *
 * <p>Failure modes are quiet:
 * <ul>
 *   <li>Neo4j absent (Optional empty) → {@code degraded=true} returned.</li>
 *   <li>Cypher fails / times out      → {@code degraded=true} + WARN log.</li>
 *   <li>Memory not owned by user      → empty {@code nodes/edges}.</li>
 * </ul>
 *
 * <p>This preserves Requirement 3.4 (Neo4j outage must NEVER cascade into
 * the main chat flow).
 */
@Configuration
public class Neo4jRelationTool {

    private static final Logger log = LoggerFactory.getLogger(Neo4jRelationTool.class);

    public static class Request {
        /** 必填：起始 memory id（必须属于当前用户） */
        public String memoryId;
        /** 路径深度；默认 2，最多 3，避免 Cypher 失控 */
        public Integer depth;
    }

    public static class Node {
        public String id;
        public String label;
        public String name;
        public Node() {}
        public Node(String id, String label, String name) { this.id = id; this.label = label; this.name = name; }
    }

    public static class Edge {
        public String from;
        public String to;
        public String type;
        public Edge() {}
        public Edge(String from, String to, String type) { this.from = from; this.to = to; this.type = type; }
    }

    public static class Response {
        public List<Node> nodes = new ArrayList<>();
        public List<Edge> edges = new ArrayList<>();
        public boolean degraded = false;
        public String message;
    }

    private final Optional<Neo4jClient> client;

    public Neo4jRelationTool(Optional<Neo4jClient> client) {
        this.client = client;
    }

    @Bean
    public FunctionCallback neo4jRelationToolCallback() {
        Function<Request, Response> fn = this::lookup;
        return FunctionCallback.builder()
                .description("Look up the People / Locations / Objects / Emotions related to a "
                        + "specific memory in the user's knowledge graph. "
                        + "Use when the user asks who/where/what is connected to a memory, or "
                        + "for resonance / similarity reasoning across the user's memories.")
                .function("neo4jRelationTool", fn)
                .inputType(Request.class)
                .build();
    }

    public Response lookup(Request req) {
        Response resp = new Response();
        if (req == null || req.memoryId == null || req.memoryId.isBlank()) {
            resp.degraded = true;
            resp.message = "memoryId required";
            return resp;
        }
        if (client.isEmpty()) {
            resp.degraded = true;
            resp.message = "Neo4j not configured";
            return resp;
        }
        String userId = MilvusSearchTool.currentUserId();
        if (userId == null) {
            resp.degraded = true;
            resp.message = "Unauthenticated";
            return resp;
        }
        int depth = req.depth == null ? 2 : Math.max(1, Math.min(3, req.depth));

        try {
            String cypher =
                    "MATCH (u:User {id: $uid})-[:OWNS]->(m:Memory {id: $mid}) " +
                    "OPTIONAL MATCH path = (m)-[*1.." + depth + "]-(other) " +
                    "WHERE NOT other:User " +
                    "WITH m, collect(DISTINCT other) AS others, collect(DISTINCT relationships(path)) AS rels " +
                    "RETURN m.id AS rootId, m.title AS rootTitle, others, rels";

            Iterable<Map<String, Object>> rs = client.get().query(cypher)
                    .bindAll(Map.of("uid", userId, "mid", req.memoryId))
                    .fetch().all();

            for (Map<String, Object> row : rs) {
                String rootId = String.valueOf(row.get("rootId"));
                resp.nodes.add(new Node(rootId, "Memory", String.valueOf(row.getOrDefault("rootTitle", ""))));

                Object others = row.get("others");
                if (others instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof org.neo4j.driver.types.Node n) {
                            String label = n.labels().iterator().hasNext() ? n.labels().iterator().next() : "Node";
                            String name = n.containsKey("name") ? n.get("name").asString()
                                    : (n.containsKey("title") ? n.get("title").asString() : "");
                            String id = String.valueOf(n.elementId());
                            resp.nodes.add(new Node(id, label, name));
                        }
                    }
                }

                Object rels = row.get("rels");
                if (rels instanceof List<?> list) {
                    for (Object r : list) {
                        if (r instanceof List<?> chain) {
                            for (Object cr : chain) {
                                if (cr instanceof org.neo4j.driver.types.Relationship rel) {
                                    resp.edges.add(new Edge(
                                            String.valueOf(rel.startNodeElementId()),
                                            String.valueOf(rel.endNodeElementId()),
                                            rel.type()));
                                }
                            }
                        }
                    }
                }
            }
            return resp;
        } catch (Exception e) {
            log.warn("neo4jRelationTool degraded: {}", e.toString());
            resp.degraded = true;
            resp.message = "Neo4j error: " + e.getClass().getSimpleName();
            return resp;
        }
    }

    /** Helper for unit tests / serialization sanity. */
    public static Map<String, Object> nodeMap(Node n) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", n.id);
        m.put("label", n.label);
        m.put("name", n.name);
        return m;
    }
}
