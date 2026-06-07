package com.mnemoscape.ai.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 实体提取请求：以记忆原始描述为主，可附结构化字段以提高准确率。
 */
public class EntityExtractRequest {

    @NotBlank
    private String description;

    /** memory.memoryLocation — 已结构化的地点，无需再从文本里猜 */
    private String memoryLocation;

    /** memory.memoryYear — 当后续需要时间相关的实体时可用 */
    private Integer memoryYear;

    public EntityExtractRequest() {}

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getMemoryLocation() { return memoryLocation; }
    public void setMemoryLocation(String memoryLocation) { this.memoryLocation = memoryLocation; }

    public Integer getMemoryYear() { return memoryYear; }
    public void setMemoryYear(Integer memoryYear) { this.memoryYear = memoryYear; }
}
