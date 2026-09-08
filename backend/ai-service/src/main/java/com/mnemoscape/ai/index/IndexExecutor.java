package com.mnemoscape.ai.index;

/**
 * R19 索引重建执行器接口 —— 抽象出"对 Milvus 做一次 drop + create"这个动作，
 * 便于测试用 mock 替换。
 *
 * <p>实现类：
 * <ul>
 *   <li>{@link MilvusIndexExecutor} —— 真实实现，HTTP 调 Milvus REST。</li>
 *   <li>测试内可以传一个 fake executor，控制 rebuild 耗时、模拟成功 / 失败。</li>
 * </ul>
 */
public interface IndexExecutor {

    /**
     * 重建 collection 的向量索引。
     *
     * <p>实现要求：
     * <ul>
     *   <li><b>幂等</b>：rebuild 期间如有并发调用，外部已通过
     *       {@link IndexRebuildService} 的 running flag 拦截；实现内部
     *       不应再做互斥。</li>
     *   <li><b>失败语义</b>：返回 {@link ExecutorResult#success=false} 时
     *       必须填 {@link ExecutorResult#error}；不要抛异常让调用方再 catch。</li>
     *   <li><b>在线查询兼容</b>：drop 旧索引 + create 新索引期间，外部 search
     *       调用不应被阻塞或失败（Milvus 自动 fallback 到 brute-force）。</li>
     * </ul>
     */
    ExecutorResult rebuild(String collectionName, MilvusIndexConfig config);

    /** 重建结果。 */
    final class ExecutorResult {
        public final boolean success;
        public final String error;
        public final String detail;

        public ExecutorResult(boolean success, String error, String detail) {
            this.success = success;
            this.error = error;
            this.detail = detail;
        }

        public static ExecutorResult ok(String detail) {
            return new ExecutorResult(true, null, detail);
        }

        public static ExecutorResult fail(String error) {
            return new ExecutorResult(false, error, null);
        }
    }
}
