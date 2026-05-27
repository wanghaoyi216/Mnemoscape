package com.mnemoscape.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 来自前端 AiMascotDock 的对话请求。
 * 关键设计：前端把"用户的最近记忆摘要"作为 context 一并送来，
 * ai-service 不再去拉 memory-service —— 避免跨服务 fan-out 也让答案天然脱敏
 * （因为前端已经按 VO 过滤）。
 */
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

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public List<MemoryDigest> getContext() { return context; }
    public void setContext(List<MemoryDigest> context) { this.context = context; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }

    public static class MemoryDigest {
        private String id;
        private String title;
        private String location;
        private Integer year;
        private String snippet;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public Integer getYear() { return year; }
        public void setYear(Integer year) { this.year = year; }
        public String getSnippet() { return snippet; }
        public void setSnippet(String snippet) { this.snippet = snippet; }
    }
}
