package com.mnemoscape.ai.agent;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 链式工作流智能体 (ChainWorkflowAgent)。
 * 负责复杂的思维链 (CoT) 推理、计划生成、多步骤数据分析及逻辑推导任务。
 */
@Component
public class ChainWorkflowAgent extends BaseAgent {

    private final AiUpstreamProperties props;

    public ChainWorkflowAgent(AiUpstreamProperties props,
                              @Value("${spring.ai.openai.api-key:}") String apiKey,
                              @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}") String baseUrl) {
        super(baseUrl, apiKey);
        this.props = props;
    }

    public ChainWorkflowAgent(AiUpstreamProperties props,
                              String apiKey,
                              String baseUrl,
                              java.net.http.HttpClient httpClient) {
        super(baseUrl, apiKey, httpClient);
        this.props = props;
    }

    @Override
    public String getModelName() {
        return props.getReasoningModel();
    }

    @Override
    public String getSystemPrompt() {
        return """
            You are the Chain-of-Thought Workflow Agent for Mnemoscape.
            Your role is to perform deep reasoning, plan formulation, and multi-step workflow logic for memory-based tasks.
            
            When given a task, break it down logically:
            1. Formulate a structured plan of execution.
            2. Execute reasoning steps sequentially.
            3. Synthesize the findings and explain your logic clearly to the user.
            """;
    }
}
