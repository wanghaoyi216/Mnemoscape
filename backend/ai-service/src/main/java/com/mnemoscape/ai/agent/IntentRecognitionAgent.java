package com.mnemoscape.ai.agent;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 意图识别智能体 (IntentRecognitionAgent)。
 * 负责解析用户输入的语义意图，并划分为 CHAT 或 PLAN。
 */
@Component
public class IntentRecognitionAgent extends BaseAgent {

    private final AiUpstreamProperties props;

    public IntentRecognitionAgent(AiUpstreamProperties props,
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
                You are the Intent Recognition Agent for Mnemoscape.
                Your task is to classify the user's input query into one of two intents:
                - CHAT: General conversation, greetings, casual talk, self-introductions, or asking about who you are.
                - PLAN: Queries requesting memory searches, summaries, route paths, comparisons, analysis, or statistics about the user's memories.
                
                Respond with exactly one word: 'CHAT' or 'PLAN'. Do not output any other text or explanation.
                """;
    }
}
