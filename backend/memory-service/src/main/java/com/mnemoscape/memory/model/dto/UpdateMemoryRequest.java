package com.mnemoscape.memory.model.dto;

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
public class UpdateMemoryRequest {
    @Size(max = 200)
    private String title;

    @Size(min = 5, message = "Memory description must be at least 5 characters")
    private String description;

    private Integer memoryYear;
    private String memoryDate;
    private String memorySeason;
    private String memoryTimeOfDay;
    private String memoryLocation;
    /** 精确坐标（可选）：编辑时若前端重新定位到街道，优先采用，避免退化成城市中心点。 */
    private Double memoryLat;
    private Double memoryLng;

    @Pattern(regexp = "(?i)PRIVATE|FRIENDS|PUBLIC", message = "Privacy level must be PRIVATE, FRIENDS, or PUBLIC")
    private String privacyLevel;

    private String sceneDataUrl;
}
