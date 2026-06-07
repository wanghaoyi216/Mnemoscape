package com.mnemoscape.ai.service;

import com.mnemoscape.ai.model.dto.AiChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务 C7 — ReActController 端到端测试。
 *
 * <p>目标：模拟 2 轮 ReAct 循环，断言事件序列正确。
 * <ul>
 *   <li>turn 0: 模型输出
 *     {@code <thought>我先搜记忆</thought><action tool="milvusSearchTool">{"query":"云南"}</action>}</li>
 *   <li>turn 1: 模型输出
 *     {@code <thought>够了</thought><action tool="final">{"answer":"找到 3 条"}</action>}</li>
 * </ul>
 *
 * <p>断言收到：
 * <ul>
 *   <li>thought × 2（turn 0 + turn 1）</li>
 *   <li>action_start × 2（milvusSearchTool + final）</li>
 *   <li>observation × 1（milvusSearchTool 的结果）</li>
 *   <li>0+ token events（取决于 final 输出的拆分）</li>
 *   <li>done × 1</li>
 *   <li>error × 0</li>
 * </ul>
 *
 * <p>为了不依赖 Spring 容器和真实 ChatClient：
 *  1) mock {@link ChatReasoner} — {@code streamReActAnswer} 返回预制 token 流
 *  2) mock {@link ToolRegistry} — {@code get("milvusSearchTool")} 返回 fake tool
 *  3) 直接 new {@link ReActController}(reasoner, registry)
 */
class ReActControllerIntegrationTest {

    private ChatReasoner reasoner;
    private ToolRegistry toolRegistry;
    private ReActController controller;

    @BeforeEach
    void setUp() {
        reasoner = mock(ChatReasoner.class);
        toolRegistry = mock(ToolRegistry.class);
        controller = new ReActController(reasoner, toolRegistry);
    }

    @Test
    @DisplayName("2 轮 ReAct 循环：milvusSearchTool → final，事件序列完整")
    void twoTurnLoopEmitsThoughtObservationActionAndDone() throws Exception {
        // ---- 准备：第 0 轮 LLM 输出 ----
        String turn0Raw = "<thought>我先搜记忆</thought>" +
                "<action tool=\"milvusSearchTool\">{\"query\":\"云南\",\"topK\":5}</action>";
        Flux<ChatReasoner.ReActEvent> turn0Flux = Flux.just(tokenEvent(turn0Raw));

        // ---- 准备：第 1 轮 LLM 输出（final） ----
        String turn1Raw = "<thought>够了</thought>" +
                "<action tool=\"final\">{\"answer\":\"找到 3 条\"}</action>";
        Flux<ChatReasoner.ReActEvent> turn1Flux = Flux.just(tokenEvent(turn1Raw));

        // ---- mock ChatReasoner.streamReActAnswer ----
        // 第 0 次调用返回 turn0Flux；第 1 次返回 turn1Flux。
        AtomicInteger callIndex = new AtomicInteger(0);
        when(reasoner.streamReActAnswer(any(AiChatRequest.class), anyString(), anyList(),
                any(), any()))
                .thenAnswer(inv -> {
                    int n = callIndex.getAndIncrement();
                    return n == 0 ? turn0Flux : turn1Flux;
                });

        // ---- mock ToolRegistry.get("milvusSearchTool") ----
        ToolRegistry.Tool fakeMilvus = mock(ToolRegistry.Tool.class);
        when(fakeMilvus.name()).thenReturn("milvusSearchTool");
        when(fakeMilvus.execute(anyString(), any()))
                .thenReturn(java.util.Map.of("hits", 3, "summaries", List.of()));
        when(toolRegistry.get("milvusSearchTool")).thenReturn(fakeMilvus);

        // ---- 收集事件 ----
        List<ChatReasoner.ReActEvent> events = new CopyOnWriteArrayList<>();
        ChatReasoner.EventListener listener = events::add;

        // ---- 执行 ----
        AiChatRequest req = new AiChatRequest();
        req.setQuestion("帮我找 2023 年在云南的记忆");
        controller.run(req, "user-1", "req-test-1", listener);

        // ---- 断言 ----
        long thought = events.stream().filter(e -> "thought".equals(e.type)).count();
        long actionStart = events.stream().filter(e -> "action_start".equals(e.type)).count();
        long observation = events.stream().filter(e -> "observation".equals(e.type)).count();
        long done = events.stream().filter(e -> "done".equals(e.type)).count();
        long error = events.stream().filter(e -> "error".equals(e.type)).count();
        long token = events.stream().filter(e -> "token".equals(e.type)).count();

        assertEquals(2, thought, "应该有 2 个 thought 事件（turn 0 + turn 1）");
        assertEquals(2, actionStart, "应该有 2 个 action_start（milvusSearchTool + final）");
        assertEquals(1, observation, "应该有 1 个 observation（milvusSearchTool 的结果）");
        assertEquals(1, done, "应该有 1 个 done");
        assertEquals(0, error, "不应该有 error 事件");

        // token 数量 >= 0 即可（"找到 3 条" 拆 1 段，所以通常 == 1）
        assertTrue(token >= 0, "token 数量非负");

        // 验证 thought 文本
        List<String> thoughts = events.stream()
                .filter(e -> "thought".equals(e.type))
                .map(e -> e.text)
                .toList();
        assertTrue(thoughts.contains("我先搜记忆"));
        assertTrue(thoughts.contains("够了"));

        // 验证 observation 拿到了 tool 返回
        ChatReasoner.ReActEvent obs = events.stream()
                .filter(e -> "observation".equals(e.type))
                .findFirst().orElseThrow();
        assertEquals("milvusSearchTool", obs.toolName);
        assertNotNullMap(obs.output);
        assertTrue(obs.text.contains("hits"));

        // 验证 done reason
        ChatReasoner.ReActEvent doneEv = events.stream()
                .filter(e -> "done".equals(e.type))
                .findFirst().orElseThrow();
        assertEquals("final-tool", doneEv.text);
    }

    @Test
    @DisplayName("6 轮还没到 final → 强制收尾：发 error + done")
    void loopExceedingMaxTurnsEmitsErrorAndDone() {
        // 永远输出非 final 的 action（死循环）
        String turnRaw = "<thought>还在想</thought>" +
                "<action tool=\"milvusSearchTool\">{\"query\":\"x\"}</action>";
        Flux<ChatReasoner.ReActEvent> loop = Flux.just(tokenEvent(turnRaw));
        when(reasoner.streamReActAnswer(any(AiChatRequest.class), anyString(), anyList(),
                any(), any()))
                .thenReturn(loop);

        ToolRegistry.Tool fakeMilvus = mock(ToolRegistry.Tool.class);
        when(fakeMilvus.name()).thenReturn("milvusSearchTool");
        doReturn(java.util.Map.of("hits", 0)).when(fakeMilvus).execute(anyString(), any());
        when(toolRegistry.get("milvusSearchTool")).thenReturn(fakeMilvus);

        List<ChatReasoner.ReActEvent> events = new CopyOnWriteArrayList<>();
        AiChatRequest req = new AiChatRequest();
        req.setQuestion("test");
        controller.run(req, "u", "r", events::add);

        long done = events.stream().filter(e -> "done".equals(e.type)).count();
        long error = events.stream().filter(e -> "error".equals(e.type)).count();
        assertEquals(1, done, "max-turns 后必须发 done");
        assertEquals(1, error, "max-turns 后必须发 error");
        assertEquals("max-turns", events.stream()
                .filter(e -> "done".equals(e.type)).findFirst().orElseThrow().text);
    }

    /* ---------------- helpers ---------------- */

    private ChatReasoner.ReActEvent tokenEvent(String text) {
        ChatReasoner.ReActEvent e = new ChatReasoner.ReActEvent();
        e.type = "react-token";
        e.text = text;
        return e;
    }

    private static void assertNotNullMap(Object o) {
        org.junit.jupiter.api.Assertions.assertNotNull(o);
        org.junit.jupiter.api.Assertions.assertTrue(o instanceof java.util.Map);
    }
}
