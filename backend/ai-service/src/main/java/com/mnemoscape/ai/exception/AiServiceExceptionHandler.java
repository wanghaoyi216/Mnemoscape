package com.mnemoscape.ai.exception;

import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiServiceExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.badRequest("Malformed JSON request"));
    }

    /**
     * 把 {@link AiUpstreamException} 转成统一的 502/503 + 结构化 body：
     * 前端据此渲染明确的"AI 暂不可用"提示，而不是让 ChatReasoner 模板冒充。
     */
    @ExceptionHandler(AiUpstreamException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleUpstream(AiUpstreamException ex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", "AI_UPSTREAM_UNAVAILABLE");
        data.put("reason", ex.getReason().name());
        data.put("requestId", UUID.randomUUID().toString());
        data.put("detail", ex.getMessage());
        ApiResponse<Map<String, Object>> body = ApiResponse.<Map<String, Object>>builder()
                .code(ex.httpStatus())
                .message("AI_UPSTREAM_UNAVAILABLE")
                .data(data)
                .requestId((String) data.get("requestId"))
                .build();
        return ResponseEntity.status(ex.httpStatus()).body(body);
    }
}
