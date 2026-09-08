package com.mnemoscape.ai.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mnemoscape.ai.config.VectorStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 真实 Milvus REST v2 索引重建执行器 —— 调 {@code /indexes/drop} +
 * {@code /indexes/create} 完成一次 drop+recreate 索引重建。
 *
 * <p><b>为什么 drop+recreate</b>：Milvus 2.4+ 的 HNSW 索引参数（{@code M} /
 * {@code efConstruction}）只能在创建时指定，运行中改参需要先 drop。Milvus 没有
 * "alter index" 这种语义；drop 之后 collection 还在，{@code entities/search}
 * 会自动 fallback 到 brute-force（性能退化但功能不挂），等 create 完即恢复
 * ANN 加速。
 *
 * <p><b>与 MilvusVectorStore.createHnswIndex 的区别</b>：那个是 bootstrap
 * 路径（collection 不存在时一次性建索引），硬编码 M=16 / efConstruction=200。
 * 这里是 R19 重建路径，读 {@link MilvusIndexConfig} 的外部化参数。
 */
public class MilvusIndexExecutor implements IndexExecutor {

    private static final Logger log = LoggerFactory.getLogger(MilvusIndexExecutor.class);

    private static final String INDEX_NAME = "vector_hnsw";
    private static final String VECTOR_FIELD = "vector";

    private final VectorStoreProperties storeProps;
    private final MilvusIndexConfig indexConfig;
    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();

    public MilvusIndexExecutor(VectorStoreProperties storeProps, MilvusIndexConfig indexConfig) {
        this.storeProps = storeProps;
        this.indexConfig = indexConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    @Override
    public ExecutorResult rebuild(String collectionName, MilvusIndexConfig config) {
        if (!storeProps.isEnabled()) {
            return ExecutorResult.fail("vector store disabled");
        }
        // 1) drop 旧索引（如果配置允许）
        if (config.isDropOnRebuild()) {
            DropResult drop = dropIndex(collectionName);
            if (!drop.success && !drop.alreadyAbsent) {
                return ExecutorResult.fail("drop failed: " + drop.error);
            }
        }
        // 2) create 新索引（带外部化参数）
        CreateResult create = createIndex(collectionName, config);
        if (!create.success) {
            return ExecutorResult.fail("create failed: " + create.error);
        }
        return ExecutorResult.ok("dropped=" + config.isDropOnRebuild() + " indexType="
                + config.getIndexType() + " M=" + config.getHnswM()
                + " efConstruction=" + config.getHnswEfConstruction());
    }

    /* ==================== drop ==================== */

    private DropResult dropIndex(String collectionName) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("collectionName", collectionName);
            if (notBlank(storeProps.getMilvusDatabase())) body.put("dbName", storeProps.getMilvusDatabase());
            body.put("indexName", INDEX_NAME);

            JsonNode resp = post("/v2/vectordb/indexes/drop", body);
            if (resp == null) return new DropResult(false, false, "empty response");
            int code = resp.path("code").asInt(-1);
            if (code == 0) return new DropResult(true, false, null);
            String msg = (resp.path("message").asText("")).toLowerCase();
            if (msg.contains("indexnotfound") || msg.contains("not found") || msg.contains("doesn't exist")) {
                return new DropResult(true, true, null);
            }
            return new DropResult(false, false, "code=" + code + " msg=" + msg);
        } catch (Exception e) {
            return new DropResult(false, false, e.toString());
        }
    }

    /* ==================== create ==================== */

    private CreateResult createIndex(String collectionName, MilvusIndexConfig config) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("collectionName", collectionName);
            if (notBlank(storeProps.getMilvusDatabase())) body.put("dbName", storeProps.getMilvusDatabase());

            ArrayNode indexParams = body.putArray("indexParams");
            ObjectNode idx = indexParams.addObject();
            idx.put("fieldName", VECTOR_FIELD);
            idx.put("indexName", INDEX_NAME);
            idx.put("indexType", config.getIndexType());
            idx.put("metricType", config.getMetricType());
            ObjectNode params = idx.putObject("params");
            if ("HNSW".equalsIgnoreCase(config.getIndexType())) {
                params.put("M", config.getHnswM());
                params.put("efConstruction", config.getHnswEfConstruction());
            } else if (config.getIndexType().toUpperCase().startsWith("IVF")) {
                params.put("nlist", config.getNlist());
            }

            JsonNode resp = post("/v2/vectordb/indexes/create", body);
            if (resp == null) return new CreateResult(false, "empty response");
            int code = resp.path("code").asInt(-1);
            if (code == 0) return new CreateResult(true, null);
            String msg = (resp.path("message").asText("")).toLowerCase();
            if (msg.contains("already exists") || msg.contains("indexalreadyexists")) {
                return new CreateResult(true, "already exists (treated as success)");
            }
            return new CreateResult(false, "code=" + code + " msg=" + msg);
        } catch (Exception e) {
            return new CreateResult(false, e.toString());
        }
    }

    /* ==================== HTTP ==================== */

    private JsonNode post(String path, ObjectNode body) throws Exception {
        String requestBody = json.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(storeProps.milvusBaseUrl() + path))
                .timeout(Duration.ofMillis(Math.max(storeProps.getMilvusTimeoutMs(), 2_000)))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (notBlank(storeProps.getMilvusToken())) {
            b.header("Authorization", "Bearer " + storeProps.getMilvusToken());
        }
        HttpResponse<byte[]> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        String bodyStr = resp.body() == null ? "" : new String(resp.body(), StandardCharsets.UTF_8);
        if (status >= 400) {
            throw new RuntimeException("Milvus HTTP " + status + " " + brief(bodyStr));
        }
        return bodyStr.isBlank() ? null : json.readTree(bodyStr);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String brief(String s) {
        return s == null ? "" : (s.length() > 240 ? s.substring(0, 240) : s);
    }

    /* ==================== 内部结果 ==================== */

    private static final class DropResult {
        final boolean success;
        final boolean alreadyAbsent;
        final String error;
        DropResult(boolean success, boolean alreadyAbsent, String error) {
            this.success = success; this.alreadyAbsent = alreadyAbsent; this.error = error;
        }
    }

    private static final class CreateResult {
        final boolean success;
        final String error;
        CreateResult(boolean success, String error) { this.success = success; this.error = error; }
    }
}
