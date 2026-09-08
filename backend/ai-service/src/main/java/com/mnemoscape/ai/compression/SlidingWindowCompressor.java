package com.mnemoscape.ai.compression;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * R10 — 滑动窗口压缩器 (SlidingWindowCompressor)。
 *
 * <p>策略：保留系统提示词 + 最近 {@code K} 轮对话；中间历史直接丢弃。
 *
 * <p>特点：
 * <ul>
 *   <li>O(1) 延迟，零外部依赖；</li>
 *   <li>失真率随窗口宽度线性下降，但完全保留语义上下文；</li>
 *   <li>适用场景：实时对话、短上下文、token 临界值的"先应急"方案。</li>
 * </ul>
 *
 * <p>默认 {@code K=10}（≈ 2000 token），与论文 "Lost in the Middle" 的折中
 * 推荐一致；可通过构造函数或 Nacos 配置覆盖。
 */
@Component
public class SlidingWindowCompressor implements ContextCompressor {

    public static final String STRATEGY_NAME = "sliding-window";

    private final int windowSize;

    public SlidingWindowCompressor() {
        this(10);
    }

    public SlidingWindowCompressor(int windowSize) {
        this.windowSize = Math.max(2, windowSize);
    }

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    @Override
    public String compress(String systemPrompt, List<Turn> turns) {
        if (turns == null || turns.isEmpty()) {
            return systemPrompt == null ? "" : systemPrompt;
        }

        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("[SYSTEM]\n").append(systemPrompt).append("\n\n");
        }

        // 滑动窗口：只取末尾 windowSize 轮
        int from = Math.max(0, turns.size() - windowSize);
        List<Turn> kept = turns.subList(from, turns.size());

        // 用省略号告知 LLM 历史被截断（缓解"模型试图引用被砍掉的内容"的幻觉）
        if (from > 0) {
            sb.append("[... ")
              .append(from)
              .append(" earlier turns truncated ...]\n\n");
        }

        for (Turn t : kept) {
            sb.append('[').append(t.role().toUpperCase()).append("]\n")
              .append(t.content())
              .append("\n\n");
        }
        return sb.toString();
    }

    public int getWindowSize() {
        return windowSize;
    }

    /** 测试/外部调用：用当前窗口估算能保留的最大 token 数 */
    public int estimatedRetainedTokens(String systemPrompt, List<Turn> turns) {
        List<Turn> kept = new ArrayList<>(turns.subList(
                Math.max(0, turns.size() - windowSize), turns.size()));
        int sum = systemPrompt == null ? 0 : estimateTokens(systemPrompt);
        for (Turn t : kept) sum += estimateTokens(t.content());
        return sum;
    }
}