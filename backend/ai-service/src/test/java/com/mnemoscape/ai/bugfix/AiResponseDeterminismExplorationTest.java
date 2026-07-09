package com.mnemoscape.ai.bugfix;

import com.mnemoscape.ai.config.AiClientConfig;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.ai.model.dto.AiChatRequest;
import com.mnemoscape.ai.service.AiCacheService;
import com.mnemoscape.ai.service.ChatReasoner;
import com.mnemoscape.ai.service.VisionDescriber;
import com.mnemoscape.ai.tools.MilvusSearchTool;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Bug condition exploration test — Bug 1 (AI 回复硬编码模板) — POST-FIX form.
 *
 * <p>BEFORE the fix, {@code ChatReasoner.generateAnswer(...)} was a pure
 * template builder that produced byte-identical output for the same prompt
 * — proving NO real LLM was being called. That is the bug counterexample.
 *
 * <p>AFTER the fix, {@code ChatReasoner} routes every non-injection prompt
 * through Spring AI's {@link ChatClient}. With the default placeholder
 * {@code NVIDIA_API_KEY} (the value shipped in {@code application.yml}),
 * the reasoner MUST refuse to fabricate an answer; it MUST raise
 * {@link AiUpstreamException} with reason {@code MISSING_KEY} so the
 * front-end gets a clear "AI 暂不可用" rather than a templated lie.
 *
 * <p>This test verifies that post-fix invariant for ALL non-injection
 * prompts in the lexicon. On the unfixed code, {@code generateAnswer}
 * returned a String — assertion would FAIL. On the fixed code, it throws
 * {@code AiUpstreamException} — assertion PASSES, proving the template
 * builder is gone.
 *
 * <p>Validates: Requirements 1.1, 1.2, 2.1, 2.2, 2.5.
 */
class AiResponseDeterminismExplorationTest {

    private static final ChatReasoner REASONER = buildReasoner();

    /**
     * Build a ChatReasoner without booting Spring. The reasoner refuses
     * to call the model when the API key is the placeholder, so the
     * ChatModel mock is never actually invoked — we just need a non-null
     * injection target for the builder.
     *
     * <p>jqwik does not honour JUnit Jupiter's {@code @BeforeAll}, so we run
     * this via a static field initializer — runs exactly once, on class load.
     */
    private static ChatReasoner buildReasoner() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ChatClient.Builder streamingBuilder = ChatClient.builder(chatModel);

        AiUpstreamProperties props = new AiUpstreamProperties();
        // Same prefix the production application.yml uses; matches the
        // shipped placeholder value.
        props.setPlaceholderKeyPrefix("nvapi-placeholder");

        Environment env = new MockEnvironment()
                .withProperty("spring.ai.openai.api-key",
                        "nvapi-placeholder-set-real-key-via-env-for-real-ai-calls");

        // Sanity: AiClientConfig's default system prompt is non-empty (regression
        // safety; the prompt-injection guard depends on it being present).
        if (AiClientConfig.DEFAULT_SYSTEM_PROMPT == null
                || AiClientConfig.DEFAULT_SYSTEM_PROMPT.isBlank()) {
            throw new IllegalStateException("DEFAULT_SYSTEM_PROMPT must not be blank");
        }
        VisionDescriber visionDescriber = new VisionDescriber(props, env, emptyProvider(), "https://integrate.api.nvidia.com");
        MilvusSearchTool milvusTool = new MilvusSearchTool(null, null, null, null);
        return new ChatReasoner(builder, streamingBuilder, props, env, visionDescriber, milvusTool, emptyProvider(), null, null, "https://integrate.api.nvidia.com");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AiCacheService> emptyProvider() {
        return (ObjectProvider<AiCacheService>) mock(ObjectProvider.class);
    }

    /** Lexicon of safe, non-prompt-injection prompts that should hit either
     *  the CHAT or PLAN intent in the heuristic classifier. */
    private static final String[] PROMPT_LEXICON = {
            "大理 2023",
            "找找类似回忆",
            "和我聊聊",
            "帮我整理本月的情绪轨迹",
            "Find my Dali memories from 2023",
            "Summarize my mood this month",
            "compare these two trips",
            "哪个朋友与我共鸣最强",
    };

    @Property(tries = 25)
    void nonInjectionPromptMustReachRealLlmPath_notTemplate(
            @ForAll("prompts") String prompt) {
        AiChatRequest req = makeRequest(prompt);

        // POST-FIX INVARIANT: with the placeholder key still in place, the
        // reasoner refuses to fabricate via templates and instead raises
        // a structured upstream exception. On the unfixed code, this call
        // returned a templated String → assertion would FAIL.
        AiUpstreamException ex = assertThrows(
                AiUpstreamException.class,
                () -> REASONER.generateAnswer(req),
                () -> "Counterexample on unfixed code: prompt '" + prompt
                        + "' produced a templated String instead of routing "
                        + "through ChatClient → AiUpstreamException."
        );

        // Reason must specifically be MISSING_KEY (placeholder still set),
        // proving the call would have gone to the model if the key were real.
        org.junit.jupiter.api.Assertions.assertEquals(
                AiUpstreamException.Reason.MISSING_KEY,
                ex.getReason(),
                "ChatReasoner must surface MISSING_KEY when api-key is the "
                        + "placeholder, proving the fix wires Spring AI ChatClient.");
    }

    @Provide
    Arbitrary<String> prompts() {
        return Arbitraries.of(PROMPT_LEXICON);
    }

    private static AiChatRequest makeRequest(String question) {
        AiChatRequest req = new AiChatRequest();
        req.setQuestion(question);
        req.setLocale("zh-CN");
        req.setContext(java.util.List.of());
        return req;
    }
}
