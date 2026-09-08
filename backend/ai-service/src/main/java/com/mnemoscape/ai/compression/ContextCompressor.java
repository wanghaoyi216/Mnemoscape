package com.mnemoscape.ai.compression;

import java.util.List;

/**
 * R10 — 上下文压缩器契约 (ContextCompressor)。
 *
 * <p>三种实现策略（见本包内具体类）：
 * <ul>
 *   <li>{@link SlidingWindowCompressor}        —— 滑动窗口：保留最近 K 条</li>
 *   <li>{@link SummaryCompressor}              —— LLM 摘要：把历史压成 ~200 字</li>
 *   <li>{@link EntityExtractionCompressor}    —— Neo4j 实体抽取：剔除冗余自然语言</li>
 * </ul>
 *
 * <p>契约要点：
 * <ol>
 *   <li>{@link #name()} 用于策略选择与监控埋点；</li>
 *   <li>{@link #estimateTokens(String)} 给上层一个 O(1) 估算 token 数；</li>
 *   <li>{@link #compress(String, List)} 永远不抛异常 — 失败时返回原文，保证对话不被截断；</li>
 *   <li>所有实现应当是 <i>无状态</i> / 线程安全的，便于在 Advisor 链里共享。</li>
 * </ol>
 */
public interface ContextCompressor {

    /** 策略名（用于 Advisor 选择策略与 Prometheus 埋点） */
    String name();

    /**
     * 估算给定文本的 token 数。中文按字符/1.6 估算，英文按空格分词/1.3 估算，
     * 整体偏保守；可被具体实现覆盖（例：摘要器在内部维护更精确的 BPE）。
     */
    default int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                cjk++;
            } else if (Character.isLetterOrDigit(ch)) {
                other++;
            }
        }
        // 中文字符 ÷ 1.6 ≈ token；其他按词数 ÷ 1.3
        int cjkTokens = (int) Math.ceil(cjk / 1.6);
        int otherTokens = (int) Math.ceil(other / 1.3);
        return cjkTokens + otherTokens;
    }

    /**
     * 压缩输入上下文。历史消息按时间顺序传入（最早在前，最近在末尾）。
     *
     * @param systemPrompt 系统提示词（可空；通常不做压缩，仅用于 token 估算）
     * @param turns        历史对话轮次（user / assistant 交替）
     * @return 压缩后的单段文本，由调用方注入 prompt
     */
    String compress(String systemPrompt, List<Turn> turns);

    /** 单轮对话：role = "user" / "assistant" / "tool" */
    record Turn(String role, String content) {
        public static Turn user(String c) { return new Turn("user", c); }
        public static Turn assistant(String c) { return new Turn("assistant", c); }
        public static Turn tool(String c) { return new Turn("tool", c); }
    }
}