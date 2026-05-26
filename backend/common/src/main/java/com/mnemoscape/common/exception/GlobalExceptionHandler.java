package com.mnemoscape.common.exception;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.MdcContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Centralised exception handling — every microservice that depends on common
 * inherits this advice automatically and produces a uniform {@link ApiResponse}
 * envelope shape on every failure path.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException ex, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        log.warn("Business exception code={} requestId={} path={} message={}",
                ex.getCode(), requestId, request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(ex.getCode())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage(), requestId));
    }

    /**
     * Spring's {@code @Cacheable(sync = true)} loader wraps any throwable from the
     * underlying lookup as {@link Cache.ValueRetrievalException}. Without unwrapping,
     * a perfectly intentional {@link BizException#notFound(String, String)} from
     * {@code MemoryLookup#findById} would skip {@link #handleBizException} and land
     * in {@link #handleGeneric}, surfacing as HTTP 500 to the client — exactly the
     * symptom reported on GET /api/v1/memories/{id} for unknown ids.
     *
     * <p>Strategy: peel one layer; if the cause is a known typed exception, delegate
     * to its dedicated handler so the response code/message stays accurate.
     */
    @ExceptionHandler(Cache.ValueRetrievalException.class)
    public ResponseEntity<ApiResponse<Void>> handleCacheRetrieval(Cache.ValueRetrievalException ex,
                                                                  HttpServletRequest request) {
        Throwable cause = ex.getCause();
        if (cause instanceof BizException biz) {
            return handleBizException(biz, request);
        }
        if (cause instanceof DataAccessException dae) {
            return handleDataAccess(dae, request);
        }
        if (cause instanceof Exception e) {
            return handleGeneric(e, request);
        }
        return handleGeneric(ex, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "Validation failed: " + errors, requestId));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex,
                                                                       HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        String errors = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "Validation failed: " + errors, requestId));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex,
                                                                HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400,
                        "Missing required parameter: " + ex.getParameterName(),
                        requestId));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400,
                        "Parameter '" + ex.getName() + "' has invalid value: " + ex.getValue(),
                        requestId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        log.debug("Malformed request body path={} reason={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "Malformed JSON request body", requestId));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                      HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(405,
                        "Method " + ex.getMethod() + " not supported for this endpoint",
                        requestId));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex,
                                                             HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404,
                        "No handler found for " + ex.getHttpMethod() + " " + ex.getRequestURL(),
                        requestId));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex,
                                                              HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404,
                        "No static resource " + ex.getResourcePath(),
                        requestId));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex,
                                                                  HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        log.warn("Data integrity violation path={} requestId={} root={}",
                request.getRequestURI(), requestId, rootMessage(ex));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(409,
                        "数据约束冲突，请确认必填字段已填、唯一字段未重复",
                        requestId));
    }

    @ExceptionHandler({UnexpectedRollbackException.class, TransactionSystemException.class})
    public ResponseEntity<ApiResponse<Void>> handleTxFailure(Exception ex, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        log.error("Transaction terminated unexpectedly path={} requestId={} type={} root={}",
                request.getRequestURI(), requestId, ex.getClass().getSimpleName(), rootMessage(ex), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500,
                        "事务执行被中止，请稍后重试；若问题持续请提交对应请求 ID 联系支持",
                        requestId));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException ex,
                                                              HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        log.error("Database error path={} requestId={}", request.getRequestURI(), requestId, ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "数据库暂时不可用，请稍后重试", requestId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        // requestId/traceId 在堆栈日志、响应体 message、响应头 X-Correlation-Id 三处同时落地，
        // 让前端用户能"一键复制信标"反馈，避免 500 黑盒。
        log.error("Unexpected error path={} requestId={} type={} message={}",
                request.getRequestURI(), requestId, ex.getClass().getName(), ex.getMessage(), ex);
        String artisticMessage = "这片记忆时空发生了坍塌，考古工具暂时无法读取。"
                + "请把这枚信标交给主理人，让我们一起把它拼回原状。信标 ID：" + requestId;
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, artisticMessage, requestId));
    }

    /** Pull from MDC (set by {@link MdcContextFilter}) so the id matches log lines. */
    private String resolveRequestId(HttpServletRequest request) {
        String mdcId = MDC.get(MdcContextFilter.MDC_CORRELATION_ID);
        if (mdcId != null && !mdcId.isBlank()) {
            return mdcId;
        }
        String header = request.getHeader(MdcContextFilter.CORRELATION_ID_HEADER);
        return header != null ? header : "unknown";
    }

    /** Walks the cause chain to surface the actual DB driver / Jackson / SQLState message. */
    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
