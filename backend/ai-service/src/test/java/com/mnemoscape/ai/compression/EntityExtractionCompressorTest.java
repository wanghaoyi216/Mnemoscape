package com.mnemoscape.ai.compression;

import com.mnemoscape.ai.model.dto.EntityExtractResponse;
import com.mnemoscape.ai.service.EntityExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R10 — EntityExtractionCompressor 单元测试。
 */
class EntityExtractionCompressorTest {

    private EntityExtractor mockExtractor;
    private EntityExtractionCompressor compressor;

    @BeforeEach
    void setUp() {
        mockExtractor = mock(EntityExtractor.class);
        compressor = new EntityExtractionCompressor(mockExtractor);
    }

    private EntityExtractResponse er(String people, String objects,
                                      String emotions, String locations) {
        EntityExtractResponse r = new EntityExtractResponse();
        r.setPeople(people == null ? null : Arrays.asList(people.split(",")));
        r.setObjects(objects == null ? null : Arrays.asList(objects.split(",")));
        r.setEmotionTags(emotions == null ? null : Arrays.asList(emotions.split(",")));
        r.setLocations(locations == null ? null : Arrays.asList(locations.split(",")));
        return r;
    }

    @Test
    @DisplayName("1) 实体压缩：把多条 turn 抽取的实体去重合并为事实清单")
    void mergesEntitiesAcrossTurns() {
        when(mockExtractor.extract(any()))
                .thenReturn(er("妈妈,朋友", "咖啡", "温暖", "大理"))
                .thenReturn(er("朋友", "雨伞,咖啡", "怀念", "大理,机场"));

        List<ContextCompressor.Turn> turns = List.of(
                ContextCompressor.Turn.user("我和妈妈在咖啡馆"),
                ContextCompressor.Turn.assistant("听起来很温暖"),
                ContextCompressor.Turn.user("对，怀念那段日子"));
        String out = compressor.compress("系统", turns);
        assertNotNull(out);
        // 应包含去重后的实体
        assertTrue(out.contains("人物"), "应有「人物」分类");
        assertTrue(out.contains("妈妈"));
        assertTrue(out.contains("朋友"));
        assertTrue(out.contains("咖啡"));
        assertTrue(out.contains("温暖"));
        assertTrue(out.contains("怀念"));
        assertTrue(out.contains("大理"));
        assertTrue(out.contains("机场"));
        // 不应重复出现「妈妈」
        int first = out.indexOf("妈妈");
        int last = out.lastIndexOf("妈妈");
        assertEquals(first, last, "实体应去重");
    }

    @Test
    @DisplayName("2) 实体压缩：保留系统提示词 + 最近一轮原文")
    void preservesSystemAndLatestTurn() {
        when(mockExtractor.extract(any()))
                .thenReturn(er("爸爸", null, null, null));

        List<ContextCompressor.Turn> turns = List.of(
                ContextCompressor.Turn.user("和爸爸吃饭"),
                ContextCompressor.Turn.user("那家餐厅的咖啡不错"));
        String out = compressor.compress("SYS", turns);
        assertTrue(out.startsWith("[SYSTEM]\nSYS"));
        assertTrue(out.contains("爸爸"));
        // 最后一轮的原文必须保留（防止模型错过当前问题）
        assertTrue(out.contains("[LATEST USER]"));
        assertTrue(out.contains("那家餐厅的咖啡不错"));
    }

    @Test
    @DisplayName("3) 实体压缩：抽取不到任何实体时给占位说明")
    void emptyEntitiesFallback() {
        when(mockExtractor.extract(any()))
                .thenReturn(new EntityExtractResponse());
        List<ContextCompressor.Turn> turns = List.of(
                ContextCompressor.Turn.user("今天天气真好啊"));
        String out = compressor.compress(null, turns);
        assertTrue(out.contains("未抽取出有效实体"));
        // 最近一轮原文仍保留
        assertTrue(out.contains("今天天气真好啊"));
    }

    @Test
    @DisplayName("4) 实体压缩：strategy name 标识")
    void strategyName() {
        assertEquals("entity-extraction", compressor.name());
    }

    @Test
    @DisplayName("5) 实体压缩：空 turns 时只输出系统提示词 + 兜底")
    void emptyTurnsProducesSystemOnly() {
        when(mockExtractor.extract(any())).thenReturn(new EntityExtractResponse());
        String out = compressor.compress("SYS", List.of());
        assertTrue(out.startsWith("[SYSTEM]\nSYS"));
        assertTrue(out.contains("未抽取出有效实体"));
    }
}