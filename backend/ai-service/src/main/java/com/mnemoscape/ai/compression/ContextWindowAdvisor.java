package com.mnemoscape.ai.compression;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * R10 — 上下文窗口 Advisor (ContextWindowAdvisor)。
 *
 * <p>Spring AI 的 {@link Advisor} 实现，包裹在 LLM 调用之前；根据当前请求的
 * 估算 token 数自动挑选压缩策略，目标是"在到达上游之前"把 prompt 压到
 * {@code softLimitTokens} 以内。
 *
 * <p>三道阈值：
 * <ol>
 *   <li>{@code token < softLimit * 0.7}        —— 直通（不压缩）；</li>
 *   <li>{@code softLimit * 0.7 <= token < limit} —— 滑动窗口（廉价）；</li>
 *   <li>{@code token >= limit}                  —— LLM 摘要 或 实体抽取（高失真/低延迟？）。</li>
 * </ol>
 *
 * <p>策略在第三档里二选一：含有大量"我想 / 我感觉 / 我希望"等情感词 → 用摘要；
 * 偏事实/问答/RAG → 用实体抽取。
 */
@Component
public class ContextWindowAdvisor implements Advisor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowAdvisor.class);

    /** Mnemoscape 的上下文窗口策略（按 Nacos 配置动态覆盖，默认给到 16k） */
    private final int softLimitTokens;
    private final int hardLimitTokens;

    private final SlidingWindowCompressor sliding;
    private final SummaryCompressor summarizer;
    private final EntityExtractionCompressor entity;

    public ContextWindowAdvisor(
            @Value("${mnemoscape.ai.compression.soft-limit-tokens:6000}") int softLimitTokens,
            @Value("${mnemoscape.ai.compression.hard-limit-tokens:12000}") int hardLimitTokens,
            SlidingWindowCompressor sliding,
            SummaryCompressor summarizer,
            EntityExtractionCompressor entity) {
        this.softLimitTokens = softLimitTokens;
        this.hardLimitTokens = hardLimitTokens;
        this.sliding = sliding;
        this.summarizer = summarizer;
        this.entity = entity;
    }

    @PostConstruct
    void init() {
        log.info("[ContextWindowAdvisor] R10 ready: soft={} hard={} strategies=[{},{},{}]",
                softLimitTokens, hardLimitTokens,
                sliding.name(), summarizer.name(), entity.name());
    }

    // ---------- Advisor contract ----------

    @Override
    public String getName() { return "R10-ContextWindowAdvisor"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 100; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest compressed = compressIfNeeded(request);
        return chain.nextCall(compressed);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest compressed = compressIfNeeded(request);
        return chain.nextStream(compressed);
    }

    // ---------- 核心逻辑 ----------

    /**
     * 把当前请求里的"历史轮次"抽取出来，跑选中的压缩器，再把 prompt 重新拼回去。
     * 抽取策略：把所有 user/assistant 文本拼成一个连续串，每隔 200 token
     * 切一刀作为"模拟 turn"。这与 Mnemoscape 现行的 ChatReasoner.prompt 构造
     * 方式一致（见 {@code ChatReasoner.buildPrompt()}）。
     */
    public ChatClientRequest compressIfNeeded(ChatClientRequest request) {
        String original = extractUserPrompt(request);
        int tokens = sliding.estimateTokens(original);
        if (tokens < softLimitTokens) {
            log.debug("[R10] pass-through, tokens={} (< soft {})", tokens, softLimitTokens);
            return request;
        }

        List<ContextCompressor.Turn> turns = pseudoTurns(original);
        ContextCompressor chosen = chooseStrategy(original, tokens);
        String systemPrompt = extractSystemPrompt(request);
        String compressed = chosen.compress(systemPrompt, turns);
        int after = sliding.estimateTokens(compressed);

        log.info("[R10] compress: {} -> {} tokens (saved {}, strategy={})",
                tokens, after, tokens - after, chosen.name());

        return rewriteRequest(request, compressed);
    }

    private ContextCompressor chooseStrategy(String text, int tokens) {
        if (tokens < softLimitTokens) {
            return sliding; // 不会到这里，保留以防 softLimit 被运行时调小
        }
        if (tokens < hardLimitTokens) {
            return sliding;
        }
        // 第三档：含强情感词 → 摘要；否则 → 实体抽取（更稳定、无外部依赖）
        String lower = text.toLowerCase();
        if (lower.contains("我感") || lower.contains("我觉")
                || lower.contains("我希望") || lower.contains("我想")
                || lower.contains("i feel") || lower.contains("i wish")) {
            return summarizer;
        }
        return entity;
    }

    /** 把一段 prompt 切成伪 turn 列表（每 ~300 token 一刀） */
    private List<ContextCompressor.Turn> pseudoTurns(String text) {
        if (text == null || text.isEmpty()) return List.of();
        // 用换行 + "User:"/"Assistant:" 标记识别真实 turn；没有则整体当 user
        List<ContextCompressor.Turn> turns = new ArrayList<>();
        String[] lines = text.split("\\n");
        StringBuilder cur = new StringBuilder();
        String curRole = "user";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("User:") || trimmed.startsWith("user:")) {
                if (cur.length() > 0) { turns.add(new ContextCompressor.Turn(curRole, cur.toString())); cur.setLength(0); }
                curRole = "user";
                cur.append(trimmed.substring(trimmed.indexOf(':') + 1).trim());
            } else if (trimmed.startsWith("Assistant:") || trimmed.startsWith("assistant:")) {
                if (cur.length() > 0) { turns.add(new ContextCompressor.Turn(curRole, cur.toString())); cur.setLength(0); }
                curRole = "assistant";
                cur.append(trimmed.substring(trimmed.indexOf(':') + 1).trim());
            } else if (!trimmed.isEmpty()) {
                if (cur.length() > 0) cur.append('\n');
                cur.append(trimmed);
            }
        }
        if (cur.length() > 0) turns.add(new ContextCompressor.Turn(curRole, cur.toString()));
        if (turns.isEmpty()) turns.add(new ContextCompressor.Turn("user", text));
        return turns;
    }

    // ---------- request mutation ----------

    /** 把压缩后的 prompt 重新塞回 ChatClientRequest。Spring AI 1.0+ 推荐做法是 mutate() */
    @SuppressWarnings("unchecked")
    private ChatClientRequest rewriteRequest(ChatClientRequest original, String newPrompt) {
        try {
            // 用 context 存储压缩后的 user text，由下游 prompt post-processor 读取
            // （不依赖 spring-ai 1.0+ 的 prompt mutate API，兼容 0.8/1.0 双版本）
            Map<String, Object> ctx = new HashMap<>(original.context());
            ctx.put("r10.compressed.user", newPrompt);
            ctx.put("r10.compressed.applied", Boolean.TRUE);
            return original.mutate().context(ctx).build();
        } catch (Throwable t) {
            // 极端情况：mutate 不可用 → 原样返回，至少保证可用性
            log.warn("[R10] failed to mutate request, returning original: {}", t.toString());
            return original;
        }
    }

    private String extractUserPrompt(ChatClientRequest req) {
        Object cached = req.context().get("r10.compressed.user");
        if (cached instanceof String s && !s.isEmpty()) return s;
        // 退化路径：从 request.prompt().getUserMessage().getText() 读
        try {
            return req.prompt().getUserMessage() == null
                    ? "" : req.prompt().getUserMessage().getText();
        } catch (Throwable t) {
            return "";
        }
    }

    private String extractSystemPrompt(ChatClientRequest req) {
        try {
            return req.prompt().getSystemMessage() == null
                    ? "" : req.prompt().getSystemMessage().getText();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------- for tests ----------

    /** 给单测用的"强制指定策略"压缩入口 */
    public String compressWith(String text, ContextCompressor forced) {
        return forced.compress(null, pseudoTurns(text));
    }

    /** RoutingAgent 调用：根据 token 数自动挑一个最便宜的策略 */
    public ContextCompressor getDefaultCompressor() {
        return sliding;
    }

    /** RoutingAgent 调用：估算 token 数（用于策略选择） */
    public int estimateTokens(String text) {
        return sliding.estimateTokens(text);
    }

    /** RoutingAgent 调用：根据 token 数直接挑策略（同步路径，不依赖 ChatClient） */
    public ContextCompressor pickStrategy(String text) {
        int t = estimateTokens(text);
        if (t < softLimitTokens) return sliding;
        if (t < hardLimitTokens) return sliding;
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("我感") || lower.contains("我觉")
                || lower.contains("我希望") || lower.contains("我想")
                || lower.contains("i feel") || lower.contains("i wish")) {
            return summarizer;
        }
        return entity;
    }

    public int getSoftLimitTokens() { return softLimitTokens; }
    public int getHardLimitTokens() { return hardLimitTokens; }

    /** Stream 包装：保留原有 reactive 语义；空操作是默认值 */
    public Mono<ChatClientResponse> adviseStreamMono(ChatClientRequest request, StreamAdvisorChain chain) {
        return Mono.from(adviseStream(request, chain));
    }
}