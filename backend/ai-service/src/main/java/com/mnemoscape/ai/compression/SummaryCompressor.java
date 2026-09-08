package com.mnemoscape.ai.compression;

import com.mnemoscape.ai.agent.BaseAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * R10 — LLM 摘要压缩器 (SummaryCompressor)。
 *
 * <p>策略：把前 N-2 轮对话喂给一个轻量 LLM 生成 200 字以内的"记忆摘要"，再
 * 拼接最后 2 轮原文。系统提示词原样保留。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>走专用的 {@code summaryModel}（默认 agenticModel），与正式对话模型解耦；</li>
 *   <li>带降级 — 上游超时/失败时返回 {@link SlidingWindowCompressor} 的结果，
 *       保证对话不被截断；</li>
 *   <li>为节省 token，本类内部做了"摘要前再压缩"：把超长 turn 截断到
 *       {@code maxCharsPerTurn} 字符，避免单轮就吃掉整个 prompt。</li>
 * </ul>
 */
@Component
public class SummaryCompressor implements ContextCompressor {

    public static final String STRATEGY_NAME = "summary";
    private static final int MAX_CHARS_PER_TURN = 1200;
    private static final int RECENT_TURNS_KEEP = 2;
    private static final String SUMMARY_SYSTEM = """
            你是一个对话历史压缩助手。请把用户提供的多轮对话压缩成不超过 200 字的中文摘要，
            保留：1) 关键事实（人物/地点/时间/事件）；2) 用户偏好与情绪基调；3) 待办或遗留问题。
            输出纯文本摘要，禁止任何标题、列表前缀或解释。
            """;

    private final BaseAgent summaryAgent;
    private final String summaryModel;
    private final SlidingWindowCompressor fallback;

    public SummaryCompressor(@Value("${spring.ai.openai.api-key:}") String apiKey,
                             @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}") String baseUrl,
                             @Value("${mnemoscape.ai.compression.summary-model:}") String summaryModel) {
        // 用 BaseAgent 的 execute() 触发同步 LLM 调用，失败时由熔断器兜底
        this.summaryAgent = new BaseAgent(baseUrl, apiKey) {
            @Override public String getModelName() {
                return (summaryModel == null || summaryModel.isBlank())
                        ? "google/gemma-3n-e2b-it" : summaryModel;
            }
            @Override public String getSystemPrompt() { return SUMMARY_SYSTEM; }
        };
        this.summaryModel = summaryModel;
        this.fallback = new SlidingWindowCompressor(RECENT_TURNS_KEEP * 2);
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
        // 短上下文直接走 fallback，避免不必要的 LLM 调用
        int totalTokens = totalTokens(systemPrompt, turns);
        if (totalTokens < 800) {
            return fallback.compress(systemPrompt, turns);
        }

        // 拆分：要摘要的旧轮次 + 保留原文的最近 N 轮
        int splitAt = Math.max(0, turns.size() - RECENT_TURNS_KEEP);
        List<Turn> oldTurns = truncate(turns.subList(0, splitAt));
        List<Turn> recentTurns = turns.subList(splitAt, turns.size());

        String summary = summarize(oldTurns);

        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("[SYSTEM]\n").append(systemPrompt).append("\n\n");
        }
        sb.append("[SUMMARY of earlier conversation]\n").append(summary).append("\n\n");
        for (Turn t : recentTurns) {
            sb.append('[').append(t.role().toUpperCase()).append("]\n")
              .append(t.content()).append("\n\n");
        }
        return sb.toString();
    }

    private String summarize(List<Turn> turns) {
        if (turns.isEmpty()) return "(无早期对话)";
        StringBuilder prompt = new StringBuilder("请压缩以下对话为不超过 200 字的中文摘要：\n\n");
        for (Turn t : turns) {
            prompt.append('[').append(t.role().toUpperCase()).append("]\n")
                  .append(t.content()).append("\n\n");
        }
        try {
            String out = summaryAgent.execute(prompt.toString(), 0.3, 512);
            if (out == null || out.isBlank()) {
                return "(摘要生成失败，已使用原文)"; // 不抛 → fallback 路径
            }
            return out.length() > 1000 ? out.substring(0, 1000) : out;
        } catch (Exception e) {
            // 摘要失败：直接用 sliding-window 兜底
            return "[fallback]\n" + fallback.compress(null, turns);
        }
    }

    private List<Turn> truncate(List<Turn> turns) {
        List<Turn> out = new ArrayList<>(turns.size());
        for (Turn t : turns) {
            String c = t.content() == null ? "" : t.content();
            out.add(new Turn(t.role(), c.length() > MAX_CHARS_PER_TURN
                    ? c.substring(0, MAX_CHARS_PER_TURN) + "...[截断]" : c));
        }
        return out;
    }

    private int totalTokens(String systemPrompt, List<Turn> turns) {
        int sum = systemPrompt == null ? 0 : estimateTokens(systemPrompt);
        for (Turn t : turns) sum += estimateTokens(t.content());
        return sum;
    }

    public String getSummaryModel() { return summaryModel; }
}