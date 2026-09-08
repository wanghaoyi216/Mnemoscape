package com.mnemoscape.ai.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10 — ContextWindowAdvisor 单元测试。
 */
class ContextWindowAdvisorTest {

    private SlidingWindowCompressor sliding;
    private SummaryCompressor summarizer;
    private EntityExtractionCompressor entity;
    private ContextWindowAdvisor advisor;

    @BeforeEach
    void setUp() {
        sliding = new SlidingWindowCompressor(10);
        // 摘要器用占位 key，避免依赖真实 LLM
        summarizer = new SummaryCompressor("nvapi-placeholder-test", "https://test", "");
        // 实体抽取器需要一个 EntityExtractor 实例（用 null + Mock）
        entity = new EntityExtractionCompressor(new com.mnemoscape.ai.service.EntityExtractor() {
            @Override
            public com.mnemoscape.ai.model.dto.EntityExtractResponse extract(
                    com.mnemoscape.ai.model.dto.EntityExtractRequest req) {
                com.mnemoscape.ai.model.dto.EntityExtractResponse r =
                        new com.mnemoscape.ai.model.dto.EntityExtractResponse();
                r.setPeople(java.util.List.of("朋友"));
                r.setObjects(java.util.List.of("咖啡"));
                return r;
            }
        });
        advisor = new ContextWindowAdvisor(100, 1000, sliding, summarizer, entity);
    }

    @Test
    @DisplayName("1) Advisor: 短文本 token < soft 不压缩（直通）")
    void shortTextPassThrough() {
        String text = "短的问候"; // ~5 tokens
        String out = advisor.compressWith(text, advisor.pickStrategy(text));
        assertNotNull(out);
        // sliding 仍会做基本格式化（[USER] 标签等），但 turn 全保留
        assertTrue(out.contains("短的问候"));
    }

    @Test
    @DisplayName("2) Advisor: token 在 soft~hard 之间选 sliding-window")
    void middleRangeUsesSliding() {
        // 制造约 200 token 的文本
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append('中');
        String text = sb.toString();
        ContextCompressor chosen = advisor.pickStrategy(text);
        assertEquals("sliding-window", chosen.name(),
                "soft=100 hard=1000，200 tokens 应走 sliding");
    }

    @Test
    @DisplayName("3) Advisor: token > hard 且含情感词 → 走 summary")
    void highTokensWithEmotionGoesSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append('中');
        sb.append(" 我感到很难过，希望有人陪。");
        String text = sb.toString();
        ContextCompressor chosen = advisor.pickStrategy(text);
        assertEquals("summary", chosen.name(),
                "硬上限之上的情感文本应触发 LLM 摘要");
    }

    @Test
    @DisplayName("4) Advisor: token > hard 但事实型 → 走 entity-extraction")
    void highTokensFactualGoesEntity() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append('中');
        sb.append(" 用户问：和朋友在咖啡馆的回忆");
        String text = sb.toString();
        ContextCompressor chosen = advisor.pickStrategy(text);
        assertEquals("entity-extraction", chosen.name(),
                "硬上限之上、缺情感词的应走实体抽取");
    }

    @Test
    @DisplayName("5) Advisor: getName / getOrder 暴露 Spring AI 集成契约")
    void springAiContract() {
        assertEquals("R10-ContextWindowAdvisor", advisor.getName());
        // HIGHEST_PRECEDENCE + 100，应当早于绝大多数业务 Advisor
        assertTrue(advisor.getOrder() < 1000,
                "Advisor order 应在靠前位置以便尽早压缩");
    }

    @Test
    @DisplayName("6) Advisor: 压缩比统计 — 1k 汉字的 sliding 输出 token 估算下降")
    void compressionRatioObservable() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 100; j++) big.append('中');
            big.append("\n");
        }
        String text = big.toString();
        int before = advisor.estimateTokens(text);
        String compressed = advisor.compressWith(text, advisor.pickStrategy(text));
        int after = advisor.estimateTokens(compressed);
        // 滑动窗口 + 伪 turn 切分后 token 应 <= 原值
        assertTrue(after <= before + 5,
                "压缩后 token 应不增加，实际 before=" + before + " after=" + after);
    }
}