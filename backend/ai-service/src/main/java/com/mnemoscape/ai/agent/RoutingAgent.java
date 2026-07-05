package com.mnemoscape.ai.agent;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 路由智能体 (RoutingAgent)。
 * 负责全局协调，先调用意图识别智能体确定任务类型，然后动态分发给增强型智能体或链式工作流智能体执行。
 */
@Component
public class RoutingAgent extends BaseAgent {

    private final IntentRecognitionAgent intentAgent;
    private final EnhancedAgent enhancedAgent;
    private final ChainWorkflowAgent chainAgent;
    private final AiUpstreamProperties props;

    public RoutingAgent(AiUpstreamProperties props,
                        IntentRecognitionAgent intentAgent,
                        EnhancedAgent enhancedAgent,
                        ChainWorkflowAgent chainAgent,
                        @Value("${spring.ai.openai.api-key:}") String apiKey,
                        @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}") String baseUrl) {
        super(baseUrl, apiKey);
        this.props = props;
        this.intentAgent = intentAgent;
        this.enhancedAgent = enhancedAgent;
        this.chainAgent = chainAgent;
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
     * 根据意图自动路由分发任务并执行。
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
        
        if (intentStr.contains("PLAN")) {
            log.info("[RoutingAgent] Routing to ChainWorkflowAgent");
            String prompt = "Task: Process user question with logic.\nContext:\n" + ragContext + "\nUser Question: " + userQuestion;
            return chainAgent.execute(prompt);
        } else {
            log.info("[RoutingAgent] Routing to EnhancedAgent");
            String prompt = "Context:\n" + ragContext + "\nUser Question: " + userQuestion;
            return enhancedAgent.execute(prompt);
        }
    }
}
