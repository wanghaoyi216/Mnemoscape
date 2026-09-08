package com.mnemoscape.ai.agent;

import com.mnemoscape.ai.compression.ContextWindowAdvisor;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 路由智能体 (RoutingAgent)。
 * 负责全局协调，先调用意图识别智能体确定任务类型，然后动态分发给增强型智能体或链式工作流智能体执行。
 *
 * <p>R10 实装：在 {@link #routeAndExecute(String, String)} 调用下游 Agent
 * <i>之前</i>，先把 "RAG context + 用户问题" 拼成的 prompt 交给
 * {@link ContextWindowAdvisor} 自动压缩（滑动窗口 / LLM 摘要 / Neo4j
 * 实体抽取 三策略之一），保证即便上下文逼近窗口上限也能稳定调用 LLM。
 */
@Component
public class RoutingAgent extends BaseAgent {

    private final IntentRecognitionAgent intentAgent;
    private final EnhancedAgent enhancedAgent;
    private final ChainWorkflowAgent chainAgent;
    private final AiUpstreamProperties props;
    private final ContextWindowAdvisor contextAdvisor;

    public RoutingAgent(AiUpstreamProperties props,
                        IntentRecognitionAgent intentAgent,
                        EnhancedAgent enhancedAgent,
                        ChainWorkflowAgent chainAgent,
                        ContextWindowAdvisor contextAdvisor,
                        @Value("${spring.ai.openai.api-key:}") String apiKey,
                        @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}") String baseUrl) {
        super(baseUrl, apiKey);
        this.props = props;
        this.intentAgent = intentAgent;
        this.enhancedAgent = enhancedAgent;
        this.chainAgent = chainAgent;
        this.contextAdvisor = contextAdvisor;
    }

    @Override
    public String getModelName() {
        return props.getAgenticModel();
    }

    @Override
    public String getSystemPrompt() {
        return "You are the Routing Agent for Mnemoscape. You coordinate and route tasks to specialized sub-agents.";
    }

    /**
     * 判定当前用户问题是否属于复杂多步规划/推理任务。
     * ChatReasoner 会据此将请求动态路由至 ChainWorkflowAgent 的响应式流式 executeStream 链路。
     *
     * <p>判定标准（保守，避免把简单问答误判为多步）：
     * <ul>
     *   <li>问题长度 ≥ 80 字符；</li>
     *   <li>且包含"多步/步骤/规划/计划/拆解/分步/依次/逐步/step by step"等关键词之一。</li>
     * </ul>
     */
    public static boolean isMultiStepTask(String userQuestion) {
        if (userQuestion == null || userQuestion.length() < 80) {
            return false;
        }
        String[] keywords = {"多步", "步骤", "规划", "计划", "拆解", "分步", "依次", "逐步",
                "step by step", "multi-step", "step-by-step", "plan", "roadmap"};
        String lower = userQuestion.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据意图自动路由分发任务并执行。
     *
     * <p>R10 — 调用 LLM 之前，先经 {@link ContextWindowAdvisor} 自动压缩
     * （滑动窗口 / 摘要 / 实体抽取 三策略之一）。压缩本身是 <i>幂等</i> 且
     * <i>异常安全</i> 的：压缩器失败时返回原文，对话不会被截断。
     */
    public String routeAndExecute(String userQuestion, String ragContext) {
        String intentStr;
        try {
            intentStr = intentAgent.execute(userQuestion).trim().toUpperCase();
        } catch (Exception e) {
            log.warn("[RoutingAgent] Intent Recognition failed, fallback to default CHAT", e);
            intentStr = "CHAT";
        }
        log.info("[RoutingAgent] Routed intent: {} for user query: {}", intentStr, userQuestion);

        boolean isPlan = intentStr.contains("PLAN");
        String rawPrompt = isPlan
                ? "Task: Process user question with logic.\nContext:\n" + ragContext
                        + "\nUser Question: " + userQuestion
                : "Context:\n" + ragContext + "\nUser Question: " + userQuestion;

        // ===== R10 自动压缩 =====
        String compressedPrompt = compressContext(rawPrompt);

        if (isPlan) {
            log.info("[RoutingAgent] Routing to ChainWorkflowAgent");
            return chainAgent.execute(compressedPrompt);
        } else {
            log.info("[RoutingAgent] Routing to EnhancedAgent");
            return enhancedAgent.execute(compressedPrompt);
        }
    }

    /**
     * R10 压缩入口：直接委托给 advisor，让它根据 token 阈值挑选最合适的策略。
     * 暴露为 package-private 是为了单测可注入假 advisor。
     */
    String compressContext(String rawPrompt) {
        if (contextAdvisor == null) return rawPrompt; // 兼容老单测
        try {
            return contextAdvisor.compressWith(rawPrompt,
                    contextAdvisor.pickStrategy(rawPrompt));
        } catch (Exception e) {
            log.warn("[RoutingAgent] R10 compression failed, fall back to raw prompt", e);
            return rawPrompt;
        }
    }
}