package com.mnemoscape.ai.compression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10 — SummaryCompressor 单元测试。
 *
 * <p>由于 SummaryCompressor 内部持有 BaseAgent（真正打 LLM），这里通过
 * 极短的输入（< 800 token）触发 fallback 路径，避免依赖外部 API。
 */
class SummaryCompressorTest {

    private SummaryCompressor newCompressor() {
        // 占位 key → BaseAgent 会立即抛 AiUpstreamException → 摘要 fallback
        return new SummaryCompressor("nvapi-placeholder-test", "https://test", "");
    }

    @Test
    @DisplayName("1) Summary: token 数 < 800 时直接走 fallback 不打 LLM")
    void shortPromptFallback() {
        SummaryCompressor c = newCompressor();
        List<ContextCompressor.Turn> turns = new ArrayList<>();
        for (int i = 0; i < 3; i++) turns.add(ContextCompressor.Turn.user("短句 " + i));
        String out = c.compress("系统", turns);
        assertNotNull(out);
        // fallback 走 SlidingWindowCompressor，输出应包含系统提示词与最近两轮
        assertTrue(out.contains("[SYSTEM]\n系统"));
        // 应包含 "T2" 之类（最近一轮）
        assertTrue(out.contains("短句"));
    }

    @Test
    @DisplayName("2) Summary: 长上下文触发摘要路径，捕获 LLM 异常 fallback")
    void longPromptTriggersSummaryPath() {
        SummaryCompressor c = newCompressor();
        List<ContextCompressor.Turn> turns = new ArrayList<>();
        // 4 段长文本，超过 800 token 阈值
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 4000; i++) big.append('中');
        turns.add(ContextCompressor.Turn.user(big.toString()));
        turns.add(ContextCompressor.Turn.assistant(big.toString()));
        turns.add(ContextCompressor.Turn.user(big.toString()));
        turns.add(ContextCompressor.Turn.assistant(big.toString()));
        turns.add(ContextCompressor.Turn.user(big.toString()));  // 最新一轮

        String out = c.compress(null, turns);
        // LLM 调用会因为占位 key 失败 → fallback → 不抛异常
        assertNotNull(out);
        // 至少应包含最近一轮的内容片段（fallback 保留原文）
        assertTrue(out.length() > 100, "压缩后输出应保留一定长度");
    }

    @Test
    @DisplayName("3) Summary: 空 turns 时仅返回系统提示词")
    void emptyTurns() {
        SummaryCompressor c = newCompressor();
        String out = c.compress("system", new ArrayList<>());
        assertEquals("system", out);
    }

    @Test
    @DisplayName("4) Summary: null turns 不抛异常")
    void nullTurns() {
        SummaryCompressor c = newCompressor();
        String out = c.compress("system", null);
        assertEquals("system", out);
    }

    @Test
    @DisplayName("5) Summary: strategy name 标识")
    void strategyName() {
        SummaryCompressor c = newCompressor();
        assertEquals("summary", c.name());
    }

    @Test
    @DisplayName("6) Summary: 超长单轮 turn 会被截断（防 prompt 爆炸）")
    void truncateLongTurns() {
        SummaryCompressor c = newCompressor();
        List<ContextCompressor.Turn> turns = new ArrayList<>();
        // 单轮 5000 字符 → truncate 后变 1200 + 截断标记
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) huge.append('a');
        turns.add(ContextCompressor.Turn.user(huge.toString()));
        turns.add(ContextCompressor.Turn.user("short"));
        // 不抛异常即可（内部走 truncate + 摘要 fallback）
        assertDoesNotThrowCompress(c, turns);
    }

    private static void assertDoesNotThrowCompress(SummaryCompressor c,
                                                    List<ContextCompressor.Turn> turns) {
        try {
            String out = c.compress(null, turns);
            assertNotNull(out);
            assertFalse(out.isEmpty());
        } catch (Exception e) {
            throw new AssertionError("compress 不应抛异常: " + e);
        }
    }
}