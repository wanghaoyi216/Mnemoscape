package com.mnemoscape.memory.controller;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import com.mnemoscape.memory.model.entity.Achievement;
import com.mnemoscape.memory.service.AchievementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/achievements")
public class AchievementController {

    private final AchievementService achievementService;

    public AchievementController(AchievementService achievementService) {
        this.achievementService = achievementService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        List<Achievement> achievements = achievementService.getUserAchievements(userId);
        List<Map<String, Object>> result = achievements.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/check")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> check(HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        List<Achievement> newlyUnlocked = achievementService.checkAndUnlock(userId);
        List<Map<String, Object>> result = newlyUnlocked.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private Map<String, Object> toMap(Achievement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("key", a.getAchievementKey());
        m.put("title", a.getTitle());
        m.put("description", a.getDescription() != null ? a.getDescription() : "");
        m.put("icon", a.getIcon() != null ? a.getIcon() : "star");
        m.put("unlockedAt", a.getUnlockedAt().toString());
        return m;
    }
}
