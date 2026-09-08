package com.mnemoscape.memory.model.dto;

import com.mnemoscape.memory.model.entity.Memory;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MemoryResponse {
    private String id;
    private String userId;
    private String title;
    private String description;
    private Integer memoryYear;
    private String memoryDate;
    private String memorySeason;
    private String memoryTimeOfDay;
    private String memoryLocation;
    /** [lng, lat] when geocoded; null otherwise */
    private double[] coords;
    private String privacyLevel;
    private boolean isLocked;
    private double fadeLevel;
    private String sceneDataUrl;
    private String emotionVectorId;
    /** AI 创建期已经 freeze 的完整 SceneReconstructionResponse JSON 字符串。
     *  非空 → SceneViewer 直接用，无需重跑 /api/v1/reconstruct（避免每次进页面卡 30s）。 */
    private String visualData;
    /** AI 创建期 freeze 的 emotion vector JSON。 */
    private String emotionProfile;
    private String createdAt;
    private String updatedAt;

    public static MemoryResponse fromEntity(Memory memory) {
        MemoryResponse response = new MemoryResponse();
        response.id = memory.getId();
        response.userId = memory.getUserId();
        response.title = memory.getTitle();
        response.description = memory.getDescription();
        response.memoryYear = memory.getMemoryYear();
        response.memoryDate = memory.getMemoryDate() != null ? memory.getMemoryDate().toString() : null;
        response.memorySeason = memory.getMemorySeason();
        response.memoryTimeOfDay = memory.getMemoryTimeOfDay();
        response.memoryLocation = memory.getMemoryLocation();
        if (memory.getMemoryLng() != null && memory.getMemoryLat() != null) {
            response.coords = new double[] { memory.getMemoryLng(), memory.getMemoryLat() };
        }
        response.privacyLevel = memory.getPrivacyLevel() != null ? memory.getPrivacyLevel().name() : null;
        response.isLocked = memory.getIsLocked() != null && memory.getIsLocked();
        response.fadeLevel = memory.getFadeLevel() != null ? memory.getFadeLevel() : 0.0;
        response.sceneDataUrl = memory.getSceneDataUrl();
        response.emotionVectorId = memory.getEmotionVectorId();
        response.visualData = memory.getVisualData();
        response.emotionProfile = memory.getEmotionProfile();
        response.createdAt = memory.getCreatedAt() != null ? memory.getCreatedAt().toString() : null;
        response.updatedAt = memory.getUpdatedAt() != null ? memory.getUpdatedAt().toString() : null;
        return response;
    }
}
