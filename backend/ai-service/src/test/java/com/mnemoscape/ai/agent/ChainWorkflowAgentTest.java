package com.mnemoscape.ai.agent;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainWorkflowAgentTest {

    private AiUpstreamProperties props;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        props = new AiUpstreamProperties();
        props.setReasoningModel("deepseek-ai/deepseek-r1");
        mockHttpClient = mock(HttpClient.class);
    }

    @Test
    @DisplayName("1) 验证 ChainWorkflowAgent 的模型名称与系统提示词")
    void testModelAndPrompt() {
        ChainWorkflowAgent agent = new ChainWorkflowAgent(props, "nvapi-real-key-123", "https://integrate.api.nvidia.com", mockHttpClient);
        assertEquals("deepseek-ai/deepseek-r1", agent.getModelName());
        assertNotNull(agent.getSystemPrompt());
        assertTrue(agent.getSystemPrompt().contains("Chain-of-Thought Workflow Agent"));
        assertTrue(agent.getSystemPrompt().contains("Formulate a structured plan"));
    }

    @Test
    @DisplayName("2) executeStream: 当 API key 为占位符或缺失时，返回包含 MISSING_KEY 的错误 Flux")
    void testExecuteStreamMissingKey() {
        ChainWorkflowAgent agentWithPlaceholder = new ChainWorkflowAgent(props, "nvapi-placeholder-key", "https://integrate.api.nvidia.com", mockHttpClient);
        Flux<String> flux = agentWithPlaceholder.executeStream("请帮我制定详细的跨时区旅行与记忆整理多步计划");

        StepVerifier.create(flux)
                .expectErrorMatches(throwable -> throwable instanceof AiUpstreamException
                        && ((AiUpstreamException) throwable).getReason() == AiUpstreamException.Reason.MISSING_KEY)
                .verify();
    }

    @Test
    @DisplayName("3) executeStream: 模拟 SSE 响应流并验证逐 token 产出 Flux<String>")
    @SuppressWarnings("unchecked")
    void testExecuteStreamSuccess() {
        ChainWorkflowAgent agent = new ChainWorkflowAgent(props, "nvapi-valid-test-key", "https://integrate.api.nvidia.com", mockHttpClient);

        HttpResponse<Stream<String>> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);

        Stream<String> sseLines = Stream.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"步骤 1：\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"收集历史碎片；\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"步骤 2：\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"进行图谱对齐。\"}}]}",
                "data: [DONE]"
        );
        when(mockResponse.body()).thenReturn(sseLines);

        CompletableFuture<HttpResponse<Stream<String>>> future = CompletableFuture.completedFuture(mockResponse);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(future);

        Flux<String> tokenFlux = agent.executeStream("长文本多步骤任务");

        StepVerifier.create(tokenFlux)
                .expectNext("步骤 1：")
                .expectNext("收集历史碎片；")
                .expectNext("步骤 2：")
                .expectNext("进行图谱对齐。")
                .verifyComplete();
    }

    @Test
    @DisplayName("4) executeStream: 当上游返回 HTTP 500 时，下游收到 UPSTREAM_ERROR 错误信号")
    @SuppressWarnings("unchecked")
    void testExecuteStreamUpstreamError() {
        ChainWorkflowAgent agent = new ChainWorkflowAgent(props, "nvapi-valid-test-key", "https://integrate.api.nvidia.com", mockHttpClient);

        HttpResponse<Stream<String>> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(500);

        CompletableFuture<HttpResponse<Stream<String>>> future = CompletableFuture.completedFuture(mockResponse);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(future);

        Flux<String> tokenFlux = agent.executeStream("测试异常流");

        StepVerifier.create(tokenFlux)
                .expectErrorMatches(t -> t instanceof AiUpstreamException
                        && ((AiUpstreamException) t).getReason() == AiUpstreamException.Reason.UPSTREAM_ERROR)
                .verify();
    }
}
