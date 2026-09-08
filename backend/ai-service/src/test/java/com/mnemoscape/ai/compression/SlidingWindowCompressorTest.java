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
 * R10 — SlidingWindowCompressor 单元测试。
 */
class SlidingWindowCompressorTest {

    @Test
    @DisplayName("1) 滑动窗口：保留最近 K 轮 + 系统提示词")
    void keepsLastKTurns() {
        SlidingWindowCompressor c = new SlidingWindowCompressor(3);
        List<ContextCompressor.Turn> turns = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            turns.add(ContextCompressor.Turn.user("T" + i));
        }
        String out = c.compress("SYS", turns);
        assertNotNull(out);
        assertTrue(out.startsWith("[SYSTEM]\nSYS"));
        assertTrue(out.contains("[... 5 earlier turns truncated ...]"),
                "应该标注被截断的轮次");
        // 只保留 T5/T6/T7
        assertTrue(out.contains("[USER]\nT5"));
        assertTrue(out.contains("[USER]\nT6"));
        assertTrue(out.contains("[USER]\nT7"));
        // 早于 5 的不应保留
        assertFalse(out.contains("T0\n"), "T0 应被砍掉");
        assertFalse(out.contains("T4\n"), "T4 应被砍掉");
    }

    @Test
    @DisplayName("2) 滑动窗口：窗口 >= 总轮数时不出现截断标注")
    void noTruncateMarkerWhenAllFit() {
        SlidingWindowCompressor c = new SlidingWindowCompressor(10);
        List<ContextCompressor.Turn> turns = List.of(
                ContextCompressor.Turn.user("A"),
                ContextCompressor.Turn.assistant("B"));
        String out = c.compress("SYS", turns);
        assertFalse(out.contains("truncated"));
        assertTrue(out.contains("[ASSISTANT]\nB"));
    }

    @Test
    @DisplayName("3) 滑动窗口：空 turns 时只输出系统提示词")
    void emptyTurns() {
        SlidingWindowCompressor c = new SlidingWindowCompressor(5);
        String out = c.compress("hello", List.of());
        assertEquals("[SYSTEM]\nhello\n\n", out);
    }

    @Test
    @DisplayName("4) 滑动窗口：estimatedRetainedTokens 在窗口内可控")
    void estimatedRetainedTokensBounded() {
        SlidingWindowCompressor c = new SlidingWindowCompressor(2);
        List<ContextCompressor.Turn> turns = List.of(
                ContextCompressor.Turn.user("a".repeat(1000)),
                ContextCompressor.Turn.user("b".repeat(1000)),
                ContextCompressor.Turn.user("c".repeat(1000)));
        int t = c.estimatedRetainedTokens("sys short", turns);
        // 窗口 2 只算 b+c 两轮，估算应远小于 3000
        assertTrue(t > 100 && t < 2000,
                "estimatedRetainedTokens 应远小于原始总和，实际：" + t);
    }

    @Test
    @DisplayName("5) 滑动窗口：estimateTokens 中文/英文混合近似")
    void estimateTokensMixed() {
        SlidingWindowCompressor c = new SlidingWindowCompressor(5);
        // 100 个汉字 + 100 个英文字母
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('中');
        for (int i = 0; i < 100; i++) sb.append('a');
        int tokens = c.estimateTokens(sb.toString());
        // 100 CJK ≈ 63 tokens；100 字母 ≈ 77 tokens；合计约 140
        assertTrue(tokens > 100 && tokens < 200,
                "估算 token 应在 100~200，实际：" + tokens);
    }

    @Test
    @DisplayName("6) 滑动窗口：name() 标识")
    void strategyName() {
        assertEquals("sliding-window", new SlidingWindowCompressor().name());
    }
}