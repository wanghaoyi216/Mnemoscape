package com.mnemoscape.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务 C6 — ToolRegistry 单元测试。
 *
 * <p>不加载 Spring 容器（避免触发 Milvus / NVIDIA API 真实依赖），
 * 直接 {@code new ToolRegistry()} + 手动 {@code register} mock 工具。
 * 这样验证 5 个核心契约：
 * <ol>
 *   <li>register → get 拿到同一个对象</li>
 *   <li>execute 返回预设值</li>
 *   <li>未注册工具 → 抛 {@link ToolRegistry.ToolNotFoundException}</li>
 *   <li>argsJson 解析失败 → 抛 {@link ToolRegistry.ToolArgsParseException}</li>
 *   <li>final 工具存在 → 返回 "answer" 字段</li>
 * </ol>
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        // 绕过 @PostConstruct —— 只测我们关心的"注册 + 执行"两条路径。
        registry = new ToolRegistry();
    }

    @Test
    @DisplayName("1) 注册一个 mock tool → get 拿到同一个对象")
    void registerThenGetReturnsSameInstance() {
        ToolRegistry.Tool mockTool = mock(ToolRegistry.Tool.class);
        when(mockTool.name()).thenReturn("mockTool");
        when(mockTool.description()).thenReturn("for unit test only");

        registry.register(mockTool);

        assertTrue(registry.has("mockTool"));
        assertSame(mockTool, registry.get("mockTool"));
        assertTrue(registry.names().contains("mockTool"));
    }

    @Test
    @DisplayName("2) 执行 mockTool：execute(argsJson, ctx) 返回预设值")
    void executeInvokesToolAndReturnsPresetValue() throws Exception {
        Map<String, Object> preset = new HashMap<>();
        preset.put("hits", 7);
        preset.put("query", "云南");

        ToolRegistry.Tool tool = mock(ToolRegistry.Tool.class);
        when(tool.name()).thenReturn("mockTool");
        when(tool.execute(anyString(), any())).thenReturn(preset);

        registry.register(tool);

        Object out = registry.get("mockTool").execute(
                "{\"query\":\"云南\",\"topK\":5}",
                new ToolRegistry.ReActContext("u1", "r1"));
        assertNotNull(out);
        assertEquals(preset, out);
    }

    @Test
    @DisplayName("3) 查询未注册工具 → 抛 ToolNotFoundException")
    void getUnregisteredThrows() {
        ToolRegistry.ToolNotFoundException ex = assertThrows(
                ToolRegistry.ToolNotFoundException.class,
                () -> registry.get("notRegistered"));
        assertTrue(ex.getMessage().contains("notRegistered"));
    }

    @Test
    @DisplayName("4) argsJson 解析失败 → 抛 ToolArgsParseException")
    void malformedArgsJsonThrowsParseException() {
        // 走内置 final 工具 —— 它没有依赖前置（不需要任何 Bean），
        // 调 execute() 时第一行就是 parseArgs(argsJson)，所以垃圾 JSON
        // 必然触发 ToolArgsParseException。toolName 出现在异常 message 里。
        ToolRegistry real = new ToolRegistry();
        real.registerBuiltinTools();
        assertTrue(real.has("final"));

        ToolRegistry.ToolArgsParseException ex = assertThrows(
                ToolRegistry.ToolArgsParseException.class,
                () -> real.get("final").execute(
                        "{not valid json",
                        new ToolRegistry.ReActContext("u1", "r1")));
        assertTrue(ex.getMessage().contains("final"));
    }

    @Test
    @DisplayName("5) final 工具存在 → 返回 answer 字段（Map 形式）")
    void finalToolReturnsAnswer() throws Exception {
        // 用真实 ToolRegistry + 走 @PostConstruct → 直接 new 后手动调一次
        ToolRegistry real = new ToolRegistry();
        real.registerBuiltinTools();

        assertTrue(real.has("final"));

        Object out = real.get("final").execute(
                "{\"answer\":\"找到 3 条关于云南的记忆\"}",
                new ToolRegistry.ReActContext("user-1", "req-1"));
        assertNotNull(out);
        assertTrue(out instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) out;
        assertEquals("找到 3 条关于云南的记忆", map.get("answer"));
    }
}
