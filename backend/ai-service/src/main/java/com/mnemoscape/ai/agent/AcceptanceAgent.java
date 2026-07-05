package com.mnemoscape.ai.agent;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 任务验收智能体 (AcceptanceAgent)。
 * 校验最终生成的回答是否准确解答了用户的提问，并返回包含 matched 和 reason 的 JSON 结构。
 */
@Component
public class AcceptanceAgent extends BaseAgent {

    private final AiUpstreamProperties props;

    public AcceptanceAgent(AiUpstreamProperties props,
                           @Value("${spring.ai.openai.api-key:}") String apiKey,
                           @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}") String baseUrl) {
        super(baseUrl, apiKey);
        this.props = props;
    }

    @Override
    public String getModelName() {
        return props.getAgenticModel();
    }

    @Override
    public String getSystemPrompt() {
        return """
            You are the Acceptance Agent (Task Validation Agent) for Mnemoscape.
            Your task is to analyze if the generated response matches and fully answers the user's initial question.
            
            Inputs:
            1. User Question
            2. Final Response
            
            You must output your analysis in JSON format with two keys:
            - "matched": boolean (true if the response matches and answers the question adequately; false otherwise)
            - "reason": string (a short explanation in Chinese of why it matched or didn't match)
            
            Do not output any markdown formatting, only output raw JSON.
            """;
    }
}
