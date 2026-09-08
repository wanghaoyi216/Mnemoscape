package com.mnemoscape.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 场景重建请求。
 *
 * <p>{@code description} 是必填的核心文本；其余字段（{@code memoryYear} /
 * {@code memorySeason} / {@code memoryTimeOfDay} / {@code memoryLocation} /
 * {@code title}）都是可选的"结构化上下文"，传给 LLM 后能让生成的环境/光线/
 * fragments 更紧扣这条记忆 —— 例如 memorySeason=WINTER 自动让 LLM 倾向
 * 选 snowy_landscape / diffuse_winter，避免把冬日记忆错配成夏季模板。
 *
 * <p>历史下限 20 字符曾让 builder 端 5-19 字符的短描述被 400 拒绝；
 * 现下限放到 5，与 MemoryBuilderView 的提交阈值对齐。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconstructRequest {
    @NotBlank(message = "Description is required")
    @Size(min = 5, max = 2000, message = "Description must be between 5 and 2000 characters")
    private String description;

    private String title;
    private Integer memoryYear;
    private String memorySeason;     // SPRING / SUMMER / AUTUMN / WINTER
    private String memoryTimeOfDay;  // MORNING / NOON / AFTERNOON / EVENING / NIGHT
    private String memoryLocation;

    /** 历史签名：仅描述。保留兼容旧调用方（如 memory-service 老路径）。 */
    public ReconstructRequest(String description) {
        this.description = description;
    }
}
