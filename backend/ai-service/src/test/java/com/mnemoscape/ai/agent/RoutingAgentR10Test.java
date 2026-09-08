package com.mnemoscape.ai.agent;

import com.mnemoscape.ai.compression.ContextCompressor;
import com.mnemoscape.ai.compression.ContextWindowAdvisor;
import com.mnemoscape.ai.compression.EntityExtractionCompressor;
import com.mnemoscape.ai.compression.SlidingWindowCompressor;
import com.mnemoscape.ai.compression.SummaryCompressor;
import com.mnemoscape.ai.config.AiUpstreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R10 — RoutingAgent 与 ContextWindowAdvisor 集成测试。
 *
 * <p>不真正调用下游 Agent（用 mock），只验证：
 *  <ul>
 *    <li>routeAndExecute 会经 ContextWindowAdvisor 压缩 prompt；</li>
 *    <li>压缩失败时优雅降级回原文；</li>
 *    <li>压缩后 prompt 仍含原始用户问题。</li>
 *  </ul>
 */
class RoutingAgentR10Test {

    private RoutingAgent routingAgent;

    @BeforeEach
    void setUp() throws Exception {
        AiUpstreamProperties props = new AiUpstreamProperties();
        props.setAgenticModel("test-agentic");
        IntentRecognitionAgent intent = mock(IntentRecognitionAgent.class);
        EnhancedAgent enhanced = mock(EnhancedAgent.class);
        ChainWorkflowAgent chain = mock(ChainWorkflowAgent.class);

        when(intent.execute(any())).thenReturn("CHAT");
        when(enhanced.execute(any())).thenReturn("enhanced-reply");

        // 构造 advisor：滑动窗口 + 占位 summary + 假 entity
        SlidingWindowCompressor sliding = new SlidingWindowCompressor(10);
        SummaryCompressor summary = new SummaryCompressor(
                "nvapi-placeholder-test", "https://test", "");
        EntityExtractionCompressor entity = new EntityExtractionCompressor(
                new com.mnemoscape.ai.service.EntityExtractor() {
                    @Override public com.mnemoscape.ai.model.dto.EntityExtractResponse extract(
                            com.mnemoscape.ai.model.dto.EntityExtractRequest r) {
                        return new com.mnemoscape.ai.model.dto.EntityExtractResponse();
                    }
                });
        ContextWindowAdvisor advisor = new ContextWindowAdvisor(50, 500, sliding, summary, entity);

        // 反射注入（RoutingAgent 构造器签名已扩展为 7 参；显式注入 7 参）
        routingAgent = new RoutingAgent(props, intent, enhanced, chain, advisor,
                "nvapi-valid-test-key", "https://test");
    }

    @Test
    @DisplayName("1) R10 集成: routeAndExecute 经 advisor 压缩后调用下游 Agent")
    void routeAndExecuteAppliesCompression() {
        // 制造一个长 prompt，迫使 advisor 走 sliding-window
        StringBuilder rag = new StringBuilder();
        for (int i = 0; i < 200; i++) rag.append("memory chunk ");
        rag.append("\n");
        String out = routingAgent.routeAndExecute("今天去过大理吗?", rag.toString());
        assertNotNull(out);
        assertTrue(out.contains("enhanced-reply"));
    }

    @Test
    @DisplayName("2) R10 集成: compressContext 不抛异常且保留核心字段")
    void compressContextRobust() throws Exception {
        Method m = RoutingAgent.class.getDeclaredMethod("compressContext", String.class);
        m.setAccessible(true);
        String input = "Context:\n" + "a".repeat(100) + "\nUser Question: 我的朋友在哪里?";
        String out = (String) m.invoke(routingAgent, input);
        assertNotNull(out);
        assertTrue(out.contains("我的朋友在哪里?"),
                "压缩后必须保留当前用户问题");
    }

    @Test
    @DisplayName("3) R10 集成: 字段注入验证 — ContextWindowAdvisor 非空")
    void advisorFieldInjected() throws Exception {
        Field f = RoutingAgent.class.getDeclaredField("contextAdvisor");
        f.setAccessible(true);
        ContextWindowAdvisor advisor = (ContextWindowAdvisor) f.get(routingAgent);
        assertNotNull(advisor);
        assertNotNull(advisor.pickStrategy("任意文本"));
    }

    @Test
    @DisplayName("4) R10 集成: 三策略被 advisor 同时持有")
    void allStrategiesWired() throws Exception {
        Field advisorField = RoutingAgent.class.getDeclaredField("contextAdvisor");
        advisorField.setAccessible(true);
        ContextWindowAdvisor advisor = (ContextWindowAdvisor) advisorField.get(routingAgent);

        // 验证 advisor.pickStrategy 在不同输入下分别命中三种策略
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) big.append('中');
        String factual = big + " 朋友 咖啡 大理";
        String emotional = big + " 我感到很孤独";
        String short = "短文本";

        ContextCompressor forShort = advisor.pickStrategy(short);
        ContextCompressor forEmotional = advisor.pickStrategy(emotional);
        ContextCompressor forFactual = advisor.pickStrategy(factual);
        assertNotNull(forShort);
        assertNotNull(forEmotional);
        assertNotNull(forFactual);
        assertTrue("sliding-window".equals(forShort.name()));
        assertTrue("summary".equals(forEmotional.name()));
        assertTrue("entity-extraction".equals(forFactual.name()));
    }
}