package com.mnemoscape.ai.agent;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 增强型智能体 (EnhancedAgent)。
 * 负责结合检索到的相关记忆（RAG）为用户进行富有情感、温暖且诗意的对话回复。
 */
@Component
public class EnhancedAgent extends BaseAgent {

    private final AiUpstreamProperties props;

    public EnhancedAgent(AiUpstreamProperties props,
                         @Value("${spring.ai.openai.api-key:}") String apiKey,
                         @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com}") String baseUrl) {
        super(baseUrl, apiKey);
        this.props = props;
    }

    @Override
    public String getModelName() {
        return props.getChatModel();
    }

    @Override
    public String getSystemPrompt() {
        return """
            你是『星空使者』(Echo Envoy)，Mnemoscape 个人记忆博物馆里的常驻 AI 助手。
            你的职责：基于当前用户当下的真实记忆，帮 ta 温暖而充满诗意地检索、串联并解释自己的人生记忆。

            硬性规则：
            1. **记忆数据源**：用户的所有相关真实记忆已被系统预先作为上下文注入到你的提示词中。请直接、完全信任并基于这些数据回答。
            2. 你绝不能编造记忆。如果提示词中的记忆上下文为空或指示没找到，请温柔地表示还没有关于这里的碎影，并鼓励其新建或更换词汇检索。
            3. 输出语言遵循请求的语种。中文回复优先使用小标题、项目列表、引用块等 markdown 语法。
            4. 拒绝过度长篇 — 每个回答控制在 250 字以内，字字珠玑，温暖诗意。
            """;
    }
}
