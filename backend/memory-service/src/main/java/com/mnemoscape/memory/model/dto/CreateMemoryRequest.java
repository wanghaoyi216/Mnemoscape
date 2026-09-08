package com.mnemoscape.memory.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateMemoryRequest {
    @NotBlank @Size(min = 5, message = "Memory description must be at least 5 characters")
    private String description;

    @NotBlank @Size(max = 200)
    private String title;

    private Integer memoryYear;
    private String memoryDate;
    private String memorySeason;
    private String memoryTimeOfDay;
    private String memoryLocation;
    /**
     * 精确坐标（可选）。当前端通过浏览器 GPS / Nominatim 正向地理编码拿到真实的
     * 街道级经纬度时一并提交，后端优先采用它，而不是再用 anchor 表把地名退化成
     * 城市中心点。这些是用户真实位置，不是客户端伪造 —— 后端仍会做范围校验。
     */
    private Double memoryLat;
    private Double memoryLng;
    @Pattern(regexp = "(?i)PRIVATE|FRIENDS|PUBLIC", message = "Privacy level must be PRIVATE, FRIENDS, or PUBLIC")
    private String privacyLevel;

    private String sceneDataUrl;
}
