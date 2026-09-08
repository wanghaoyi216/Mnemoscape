package com.mnemoscape.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实体提取请求：以记忆原始描述为主，可附结构化字段以提高准确率。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EntityExtractRequest {

    @NotBlank
    private String description;

    /** memory.memoryLocation — 已结构化的地点，无需再从文本里猜 */
    private String memoryLocation;

    /** memory.memoryYear — 当后续需要时间相关的实体时可用 */
    private Integer memoryYear;
}
