package com.mnemoscape.ai.index;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 索引参数外部化配置 —— R19 增量索引重建策略。
 *
 * <p>把 HNSW 索引的"建索引时"参数（{@code M} / {@code efConstruction}）和
 * "查询时"参数（{@code ef}）从代码常量提到 Nacos / application.yml，运营调参
 * 不用改代码、不用重启服务。
 *
 * <p>绑定路径：{@code mnemoscape.ai.vector.index}
 *
 * <p><b>为什么需要 ef</b>：Milvus HNSW 的 {@code ef} 是查询阶段参数（搜索时取的
 * 候选邻居数，越大越精确越慢），与建索引时的 {@code efConstruction} 是两个独立
 * 概念。Milvus 在 {@code /indexes/describe} 之外没有专门的"查询参数"接口 —
 * ef 通过 search 请求的 {@code params} 字段传入，Java 侧记一份配置便于在
 * search 调用里自动注入。
 *
 * <p><b>nlist 说明</b>：{@code nlist} 是 IVF 系索引（IVF_FLAT / IVF_PQ / IVF_SQ8）
 * 的聚类中心数。HNSW 不使用 nlist —— 本配置里的 nlist 仅在切换到 IVF 索引时生效，
 * 目前 {@code indexType=HNSW} 不会下发该参数。
 */
@Configuration
@RefreshScope
@ConfigurationProperties(prefix = "mnemoscape.ai.vector.index")
public class MilvusIndexConfig {

    /* ==================== 索引类型 ==================== */

    /** 索引类型：HNSW（默认）/ IVF_FLAT / IVF_PQ / IVF_SQ8 / AUTOINDEX。 */
    private String indexType = "HNSW";

    /** 向量距离度量：COSINE / L2 / IP。Mnemoscape 走 COSINE。 */
    private String metricType = "COSINE";

    /* ==================== HNSW 调优参数 ==================== */

    /**
     * HNSW 每个节点的最大邻居数（典型 8~64）。越大召回越准、内存越高、建索引越慢。
     * 4096 维 + 5w 节点推荐 16~24；10w+ 推荐 32。
     */
    private int hnswM = 16;

    /**
     * 建索引时的候选队列大小（典型 100~400）。越大建索引越慢、图质量越好。
     * 4096 维 + 5w 节点推荐 200。
     */
    private int hnswEfConstruction = 200;

    /**
     * 查询时的候选邻居数（典型 32~512）。越大召回越准、延迟越高。
     * 一般设成 topK 的 5~10 倍；这里给个保守默认 128，搜索时按 topK 动态调整。
     */
    private int hnswEf = 128;

    /* ==================== IVF 调优参数（备选）==================== */

    /** IVF 聚类中心数（仅 IVF_* 索引使用）。一般 = 4*sqrt(N)，N=5w 时约 900。 */
    private int nlist = 1024;

    /* ==================== 重建策略 ==================== */

    /**
     * 累计多少条 upsert 后触发一次后台重建。R19 设计：1000 条。
     * 触发后只标 dirty=true，真正重建由 {@link IndexRebuildService} 周期任务
     * 或管理员主动触发执行（避免在线写入热路径上同步重建）。
     */
    private int dirtyThreshold = 1000;

    /**
     * 后台重建 Cron 表达式。R19 设计：每天凌晨 3 点（业务低峰）。
     * 调小可改成 {@code "0 0/1 * * *"}（每小时）做压力测试。
     */
    private String rebuildCron = "0 3 * * *";

    /**
     * 重建时是否允许删除旧索引（drop+recreate）。
     * Milvus HNSW 不支持原地改参；true=先 drop 再 create（短暂无索引，查询降级
     * 到 brute-force 扫描），false=跳过参数变更（保留旧索引）。
     */
    private boolean dropOnRebuild = true;

    /**
     * 重建时是否使用副本同步建索引（需 Milvus 配置 queryNode 资源足够）。
     * true=在线重建（推荐），false=离线重建（会短暂不可用）。
     */
    private boolean onlineRebuild = true;

    /* ==================== 容量 / 性能 ==================== */

    /**
     * 一次重建的最大节点数（防御性上限）。超过则分批重建。
     * 5w 节点单批可承受；10w+ 应分批（Milvus rebuild 接口本身就是阻塞的）。
     */
    private int maxRebuildBatchSize = 100_000;

    /* ==================== getter / setter ==================== */

    public String getIndexType() { return indexType; }
    public void setIndexType(String indexType) { this.indexType = indexType; }

    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }

    public int getHnswM() { return hnswM; }
    public void setHnswM(int hnswM) { this.hnswM = Math.max(2, Math.min(hnswM, 64)); }

    public int getHnswEfConstruction() { return hnswEfConstruction; }
    public void setHnswEfConstruction(int ef) {
        this.hnswEfConstruction = Math.max(16, Math.min(ef, 1000));
    }

    public int getHnswEf() { return hnswEf; }
    public void setHnswEf(int hnswEf) { this.hnswEf = Math.max(8, Math.min(hnswEf, 1024)); }

    public int getNlist() { return nlist; }
    public void setNlist(int nlist) { this.nlist = Math.max(1, Math.min(nlist, 65536)); }

    public int getDirtyThreshold() { return dirtyThreshold; }
    public void setDirtyThreshold(int dirtyThreshold) {
        this.dirtyThreshold = Math.max(1, dirtyThreshold);
    }

    public String getRebuildCron() { return rebuildCron; }
    public void setRebuildCron(String rebuildCron) { this.rebuildCron = rebuildCron; }

    public boolean isDropOnRebuild() { return dropOnRebuild; }
    public void setDropOnRebuild(boolean dropOnRebuild) { this.dropOnRebuild = dropOnRebuild; }

    public boolean isOnlineRebuild() { return onlineRebuild; }
    public void setOnlineRebuild(boolean onlineRebuild) { this.onlineRebuild = onlineRebuild; }

    public int getMaxRebuildBatchSize() { return maxRebuildBatchSize; }
    public void setMaxRebuildBatchSize(int maxRebuildBatchSize) {
        this.maxRebuildBatchSize = Math.max(1000, maxRebuildBatchSize);
    }

    @Override
    public String toString() {
        return "MilvusIndexConfig{" +
                "indexType='" + indexType + '\'' +
                ", metricType='" + metricType + '\'' +
                ", hnswM=" + hnswM +
                ", hnswEfConstruction=" + hnswEfConstruction +
                ", hnswEf=" + hnswEf +
                ", nlist=" + nlist +
                ", dirtyThreshold=" + dirtyThreshold +
                ", rebuildCron='" + rebuildCron + '\'' +
                ", dropOnRebuild=" + dropOnRebuild +
                ", onlineRebuild=" + onlineRebuild +
                ", maxRebuildBatchSize=" + maxRebuildBatchSize +
                '}';
    }
}
