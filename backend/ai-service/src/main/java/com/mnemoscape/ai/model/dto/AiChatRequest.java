package com.mnemoscape.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 来自前端 AiMascotDock 的对话请求。
 * 关键设计：前端把"用户的最近记忆摘要"作为 context 一并送来，
 * ai-service 不再去拉 memory-service —— 避免跨服务 fan-out 也让答案天然脱敏
 * （因为前端已经按 VO 过滤）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiChatRequest {

    @NotBlank
    private String question;

    /** 可选 — 当前用户最近 N 条记忆的精简版（标题/位置/年份/简介） */
    private List<MemoryDigest> context;

    /** 可选 — 客户端期望语言：zh / en，缺省 zh */
    private String locale;

    /**
     * 可选 — 用户附件图片 URL 列表（来自 asset-service 的 presigned URL）。
     * 非空时，{@code ChatReasoner} 会先调一次视觉模型（默认 Qwen3.5-VL）做
     * "图片→密集描述"，再把描述 prepend 到 prompt 喂给基座模型 (M2.7)。
     * 这样保留 M2.7 的中文/agentic 优势，同时具备真正的视觉理解能力。
     */
    private List<String> images;

    /**
     * 可选 — 启用 ReAct 自主循环协议（任务 C4）。当 {@code true} 时，
     * ChatController 走 {@code ReActController.run()} 路径；模型按
     * {@code REACT_PROTOCOL_PROMPT} 输出 think→act→observe 标签，
     * 后端解析标签、调工具、把结果当 observation 喂回下一轮。
     * 默认 true 启用 ReAct 循环保证工具调用生效。
     */
    @Builder.Default
    private Boolean reAct = true;

    /**
     * 可选 — 启用动态工作流与并行子智能体。
     */
    @Builder.Default
    private Boolean dynamicWorkflow = false;

    /** 兼容 Boolean 字段的 boolean 取值（避免 unbox NPE）。 */
    public boolean isReAct() { return Boolean.TRUE.equals(reAct); }

    public boolean isDynamicWorkflow() { return Boolean.TRUE.equals(dynamicWorkflow); }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MemoryDigest {
        private String id;
        private String title;
        private String location;
        private Integer year;
        private String snippet;
    }
}
