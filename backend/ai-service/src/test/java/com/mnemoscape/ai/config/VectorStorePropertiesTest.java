package com.mnemoscape.ai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VectorStoreProperties 默认值回归测试 —— 对应任务 B1。
 *
 * <p>{@code application.yml} 实际配的是 {@code nvidia/nv-embed-v1} / 4096，
 * Java 类的字段默认值必须与 yml 一致，否则两处 drift 会让 Milvus collection
 * 维度对不上 → 第一次 upsert 就 422。
 */
class VectorStorePropertiesTest {

    @Test
    void defaults_match_nv_embed_v1_4096() {
        VectorStoreProperties props = new VectorStoreProperties();
        assertEquals("nvidia/nv-embed-v1", props.getEmbeddingModel());
        assertEquals(4096, props.getEmbeddingDimension());
    }

    @Test
    void enabled_default_is_true() {
        // enabled=true 是默认行为；false 时 MilvusSearchTool 走关键词降级。
        VectorStoreProperties props = new VectorStoreProperties();
        assertTrue(props.isEnabled());
    }
}
