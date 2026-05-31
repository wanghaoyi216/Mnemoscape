package com.mnemoscape.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.config.VectorStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Milvus 向量库客户端（REST API v2 over JDK HttpClient）。
 *
 * <p>用 Milvus 2.4+ 的 RESTful v2 接口（{@code /v2/vectordb/...}，与 gRPC 复用
 * 19530 端口），通过裸 {@link HttpClient} 调用 —— 不引 milvus-java-sdk，避免
 * 其 gRPC / protobuf / netty-shaded 依赖与 Spring Cloud 栈冲突。
 *
 * <p><b>Collection schema（quick setup + 动态字段）</b>：
 * <ul>
 *   <li>主键 {@code id}（VarChar，= memoryId）</li>
 *   <li>{@code vector}（FloatVector，维度 = {@link VectorStoreProperties#getEmbeddingDimension()}）</li>
 *   <li>动态标量字段：{@code user_id} / {@code title} / {@code location} / {@code year} / {@code snippet}</li>
 * </ul>
 * 度量用 COSINE（embedding 已归一化时等价于点积）。
 *
 * <p><b>降级</b>：所有方法都吞掉异常（记日志），让调用方拿到 false / 空列表后退回
 * 关键词检索。collection 自检（{@link #ensureCollection()}）只跑一次，失败后
 * {@link #available} 置 false，后续调用直接短路。
 */
@Service
public class MilvusVectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final VectorStoreProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();

    /** collection 是否确认就绪（懒初始化，只查一次）。 */
    private final AtomicBoolean collectionReady = new AtomicBoolean(false);
    /** Milvus 整体是否可用（首次失败后置 false 避免每次都打满超时）。 */
    private volatile boolean available = true;

    public MilvusVectorStore(VectorStoreProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    /** 一条待索引的记忆向量记录。 */
    public static class VectorRecord {
        public String memoryId;
        public String userId;
        public String title;
        public String location;
        public Integer year;
        public String snippet;
        /** 隐私级别（PUBLIC / FRIENDS / PRIVATE）；用于共鸣大厅的跨用户公共检索过滤。 */
        public String privacy;
        public float[] vector;
    }

    /** 检索命中。 */
    public static class SearchHit {
        public String memoryId;
        public String userId;
        public String title;
        public String location;
        public Integer year;
        public String snippet;
        public double score;
    }

    public boolean isEnabled() {
        return props.isEnabled() && available;
    }

    /* ============================ 写入 ============================ */

    /**
     * Upsert 一条记忆向量。失败返回 false（best-effort，不抛）。
     */
    public boolean upsert(VectorRecord rec) {
        if (!isEnabled() || rec == null || rec.vector == null || rec.vector.length == 0) return false;
        if (!ensureCollection()) return false;
        try {
            ObjectNode body = json.createObjectNode();
            body.put("collectionName", props.getCollectionName());
            if (notBlank(props.getMilvusDatabase())) body.put("dbName", props.getMilvusDatabase());

            ArrayNode data = body.putArray("data");
            ObjectNode entity = data.addObject();
            entity.put("id", safe(rec.memoryId));
            ArrayNode vec = entity.putArray("vector");
            for (float v : rec.vector) vec.add(v);
            entity.put("user_id", safe(rec.userId));
            entity.put("title", clip(rec.title, 480));
            entity.put("location", clip(rec.location, 240));
            entity.put("year", rec.year == null ? 0 : rec.year);
            entity.put("snippet", clip(rec.snippet, 900));
            entity.put("privacy", rec.privacy == null ? "PRIVATE" : rec.privacy);

            JsonNode resp = post("/v2/vectordb/entities/upsert", body);
            boolean ok = resp != null && resp.path("code").asInt(-1) == 0;
            if (!ok) log.warn("[Milvus] upsert non-zero code for memory {}: {}", rec.memoryId, brief(resp));
            return ok;
        } catch (Exception e) {
            log.warn("[Milvus] upsert failed for memory {}: {}. Disabling vector store.", rec.memoryId, e.toString());
            available = false;
            return false;
        }
    }

    /** 删除一条记忆向量（按主键 id）。失败返回 false。 */
    public boolean deleteById(String memoryId) {
        if (!isEnabled() || memoryId == null || memoryId.isBlank()) return false;
        if (!ensureCollection()) return false;
        try {
            ObjectNode body = json.createObjectNode();
            body.put("collectionName", props.getCollectionName());
            if (notBlank(props.getMilvusDatabase())) body.put("dbName", props.getMilvusDatabase());
            body.put("filter", "id == \"" + escape(memoryId) + "\"");

            JsonNode resp = post("/v2/vectordb/entities/delete", body);
            boolean ok = resp != null && resp.path("code").asInt(-1) == 0;
            if (!ok) log.warn("[Milvus] delete non-zero code for memory {}: {}", memoryId, brief(resp));
            return ok;
        } catch (Exception e) {
            log.warn("[Milvus] delete failed for memory {}: {}. Disabling vector store.", memoryId, e.toString());
            available = false;
            return false;
        }
    }

    /* ============================ 检索 ============================ */

    /**
     * 向量相似度检索，限定 {@code user_id == userId}。
     *
     * @return 命中列表（已带 score，COSINE 距离直接当相似度）；失败 / 不可用返回 null
     *         （null 与"空列表"语义不同：null = 走降级，空 = 真没命中）。
     */
    public List<SearchHit> search(float[] queryVector, String userId, int topK) {
        if (!isEnabled() || queryVector == null || queryVector.length == 0 || userId == null) return null;
        if (!ensureCollection()) return null;
        try {
            int limit = Math.max(1, Math.min(topK <= 0 ? props.getDefaultTopK() : topK, 50));

            ObjectNode body = json.createObjectNode();
            body.put("collectionName", props.getCollectionName());
            if (notBlank(props.getMilvusDatabase())) body.put("dbName", props.getMilvusDatabase());
            body.put("annsField", "vector");
            body.put("limit", limit);
            body.put("filter", "user_id == \"" + escape(userId) + "\"");

            ArrayNode dataArr = body.putArray("data");
            ArrayNode qv = dataArr.addArray();
            for (float v : queryVector) qv.add(v);

            ArrayNode out = body.putArray("outputFields");
            out.add("id"); out.add("user_id"); out.add("title");
            out.add("location"); out.add("year"); out.add("snippet");

            JsonNode resp = post("/v2/vectordb/entities/search", body);
            if (resp == null || resp.path("code").asInt(-1) != 0) {
                log.warn("[Milvus] search non-zero code: {}", brief(resp));
                return null;
            }
            List<SearchHit> hits = new ArrayList<>();
            JsonNode rows = resp.path("data");
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    SearchHit h = new SearchHit();
                    h.memoryId = row.path("id").asText(null);
                    h.userId = row.path("user_id").asText(null);
                    h.title = row.path("title").asText("");
                    h.location = row.path("location").asText("");
                    int y = row.path("year").asInt(0);
                    h.year = y == 0 ? null : y;
                    h.snippet = row.path("snippet").asText("");
                    // REST v2 把相似度放在 "distance"；COSINE 越大越相似。
                    h.score = row.path("distance").asDouble(row.path("score").asDouble(0.0));
                    if (h.memoryId != null) hits.add(h);
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("[Milvus] search failed: {}. Disabling vector store.", e.toString());
            available = false;
            return null;
        }
    }

    /**
     * 跨用户公共向量检索 — 给共鸣大厅用。只召回 {@code privacy == "PUBLIC"} 且
     * {@code user_id != excludeUserId}（排除自己）的记忆。
     *
     * @return 命中列表；失败 / 不可用返回 null（走降级）。
     */
    public List<SearchHit> searchPublic(float[] queryVector, String excludeUserId, int topK) {
        if (!isEnabled() || queryVector == null || queryVector.length == 0) return null;
        if (!ensureCollection()) return null;
        try {
            int limit = Math.max(1, Math.min(topK <= 0 ? props.getDefaultTopK() : topK, 50));

            ObjectNode body = json.createObjectNode();
            body.put("collectionName", props.getCollectionName());
            if (notBlank(props.getMilvusDatabase())) body.put("dbName", props.getMilvusDatabase());
            body.put("annsField", "vector");
            // 多取一些，便于在 Java 侧排除自己后仍有足够候选。
            body.put("limit", Math.min(limit + 10, 60));
            String filter = "privacy == \"PUBLIC\"";
            if (excludeUserId != null && !excludeUserId.isBlank()) {
                filter += " and user_id != \"" + escape(excludeUserId) + "\"";
            }
            body.put("filter", filter);

            ArrayNode dataArr = body.putArray("data");
            ArrayNode qv = dataArr.addArray();
            for (float v : queryVector) qv.add(v);

            ArrayNode out = body.putArray("outputFields");
            out.add("id"); out.add("user_id"); out.add("title");
            out.add("location"); out.add("year"); out.add("snippet");

            JsonNode resp = post("/v2/vectordb/entities/search", body);
            if (resp == null || resp.path("code").asInt(-1) != 0) {
                log.warn("[Milvus] public search non-zero code: {}", brief(resp));
                return null;
            }
            List<SearchHit> hits = new ArrayList<>();
            JsonNode rows = resp.path("data");
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    SearchHit h = new SearchHit();
                    h.memoryId = row.path("id").asText(null);
                    h.userId = row.path("user_id").asText(null);
                    h.title = row.path("title").asText("");
                    h.location = row.path("location").asText("");
                    int y = row.path("year").asInt(0);
                    h.year = y == 0 ? null : y;
                    h.snippet = row.path("snippet").asText("");
                    h.score = row.path("distance").asDouble(row.path("score").asDouble(0.0));
                    if (h.memoryId != null) hits.add(h);
                }
            }
            if (hits.size() > limit) return hits.subList(0, limit);
            return hits;
        } catch (Exception e) {
            log.warn("[Milvus] public search failed: {}. Disabling vector store.", e.toString());
            available = false;
            return null;
        }
    }

    /* ============================ collection 自检 ============================ */

    /**
     * 确保目标 collection 存在；不存在则用 quick-setup 建一个（带动态字段）。
     * 只成功一次后短路；任何失败把 {@link #available} 置 false。
     */
    boolean ensureCollection() {
        if (collectionReady.get()) return true;
        synchronized (this) {
            if (collectionReady.get()) return true;
            try {
                ObjectNode hasBody = json.createObjectNode();
                hasBody.put("collectionName", props.getCollectionName());
                if (notBlank(props.getMilvusDatabase())) hasBody.put("dbName", props.getMilvusDatabase());

                JsonNode hasResp = post("/v2/vectordb/collections/has", hasBody);
                boolean exists = hasResp != null
                        && hasResp.path("code").asInt(-1) == 0
                        && hasResp.path("data").path("has").asBoolean(false);

                if (!exists) {
                    if (!createCollection()) return false;
                }
                collectionReady.set(true);
                log.info("[Milvus] collection '{}' ready (exists={})", props.getCollectionName(), exists);
                return true;
            } catch (Exception e) {
                log.warn("[Milvus] ensureCollection failed; disabling vector store: {}", e.toString());
                available = false;
                return false;
            }
        }
    }

    private boolean createCollection() {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("collectionName", props.getCollectionName());
            if (notBlank(props.getMilvusDatabase())) body.put("dbName", props.getMilvusDatabase());
            body.put("dimension", props.getEmbeddingDimension());
            body.put("metricType", "COSINE");
            body.put("idType", "VarChar");
            body.put("primaryFieldName", "id");
            body.put("vectorFieldName", "vector");
            body.put("autoID", false);
            body.put("enableDynamicField", true);
            ObjectNode params = body.putObject("params");
            params.put("max_length", 128);

            JsonNode resp = post("/v2/vectordb/collections/create", body);
            boolean ok = resp != null && resp.path("code").asInt(-1) == 0;
            if (ok) {
                log.info("[Milvus] created collection '{}' dim={}",
                        props.getCollectionName(), props.getEmbeddingDimension());
            } else {
                log.warn("[Milvus] createCollection non-zero code: {}", brief(resp));
            }
            return ok;
        } catch (Exception e) {
            log.warn("[Milvus] createCollection failed: {}", e.toString());
            return false;
        }
    }

    /* ============================ HTTP ============================ */

    private JsonNode post(String path, ObjectNode body) throws Exception {
        String requestBody = json.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(props.milvusBaseUrl() + path))
                .timeout(Duration.ofMillis(Math.max(props.getMilvusTimeoutMs(), 2_000)))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (notBlank(props.getMilvusToken())) {
            b.header("Authorization", "Bearer " + props.getMilvusToken());
        }
        HttpResponse<byte[]> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        String responseJson = resp.body() == null ? "" : new String(resp.body(), StandardCharsets.UTF_8);
        if (status >= 400) {
            throw new RuntimeException("Milvus REST HTTP " + status + " "
                    + (responseJson.length() > 240 ? responseJson.substring(0, 240) : responseJson));
        }
        return responseJson.isBlank() ? null : json.readTree(responseJson);
    }

    /* ============================ utils ============================ */

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String escape(String s) { return s == null ? "" : s.replace("\"", "\\\""); }
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
    private static String brief(JsonNode n) {
        if (n == null) return "null";
        String s = n.toString();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    /** 暴露给测试：当前状态快照。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.isEnabled());
        m.put("available", available);
        m.put("collectionReady", collectionReady.get());
        m.put("collection", props.getCollectionName());
        m.put("dimension", props.getEmbeddingDimension());
        return m;
    }
}
