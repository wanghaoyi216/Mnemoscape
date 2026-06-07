package com.mnemoscape.ai.controller;

import com.mnemoscape.ai.service.DiaryGeneratorService;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AI 日记生成 API。
 */
@RestController
@RequestMapping("/api/v1/diary")
public class DiaryController {

    private final DiaryGeneratorService diaryService;

    public DiaryController(DiaryGeneratorService diaryService) {
        this.diaryService = diaryService;
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<DiaryGeneratorService.DiaryEntry>> generate(
            @RequestBody(required = false) DiaryGeneratorService.DiaryRequest request,
            HttpServletRequest httpReq) {

        String userId = RequestContext.requireUserId(httpReq);

        if (request == null) {
            request = new DiaryGeneratorService.DiaryRequest();
        }
        request.userId = userId;

        DiaryGeneratorService.DiaryEntry entry = diaryService.generate(request);
        return ResponseEntity.ok(ApiResponse.success(entry));
    }
}
