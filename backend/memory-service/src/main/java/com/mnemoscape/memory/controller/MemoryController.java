package com.mnemoscape.memory.controller;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.dto.PageResult;
import com.mnemoscape.common.web.RequestContext;
import com.mnemoscape.memory.model.dto.CreateMemoryRequest;
import com.mnemoscape.memory.model.dto.MemoryFragmentResponse;
import com.mnemoscape.memory.model.dto.MemoryResponse;
import com.mnemoscape.memory.model.dto.MemoryVersionResponse;
import com.mnemoscape.memory.model.dto.UpdateMemoryRequest;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.service.MemoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {
    private final MemoryService memoryService;
    private final com.mnemoscape.memory.repository.MemoryRepository memoryRepository;

    public MemoryController(MemoryService memoryService,
                            com.mnemoscape.memory.repository.MemoryRepository memoryRepository) {
        this.memoryService = memoryService;
        this.memoryRepository = memoryRepository;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MemoryResponse>> create(@Valid @RequestBody CreateMemoryRequest request,
                                                       HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        Memory memory = memoryService.createMemory(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(MemoryResponse.fromEntity(memory)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<MemoryResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String privacyLevel,
            HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        Page<Memory> result = memoryService.listMemories(userId, page, size, privacyLevel);
        List<MemoryResponse> items = result.getContent().stream()
                .map(MemoryResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(
                PageResult.of(items, result.getTotalElements(), page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MemoryResponse>> get(@PathVariable String id, HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(MemoryResponse.fromEntity(
                memoryService.getMemory(id, userId))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<MemoryResponse>> update(@PathVariable String id,
                                                       @Valid @RequestBody UpdateMemoryRequest request,
                                                       HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(MemoryResponse.fromEntity(
                memoryService.updateMemory(id, request, userId))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id, HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        memoryService.deleteMemory(id, userId);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }

    @PostMapping("/{id}/lock")
    public ResponseEntity<ApiResponse<MemoryResponse>> lock(@PathVariable String id, HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(MemoryResponse.fromEntity(
                memoryService.lockMemory(id, userId))));
    }

    @DeleteMapping("/{id}/lock")
    public ResponseEntity<ApiResponse<MemoryResponse>> unlock(@PathVariable String id, HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(MemoryResponse.fromEntity(
                memoryService.unlockMemory(id, userId))));
    }

    @GetMapping("/{id}/drift")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDrift(@PathVariable String id,
                                                                      HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(memoryService.getDriftState(id, userId)));
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<ApiResponse<List<MemoryVersionResponse>>> listVersions(@PathVariable String id,
                                                                          HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        List<MemoryVersionResponse> versions = memoryService.getVersions(id, userId).stream()
                .map(MemoryVersionResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(versions));
    }

    @PostMapping("/{id}/restore/{versionNumber}")
    public ResponseEntity<ApiResponse<MemoryResponse>> restoreVersion(@PathVariable String id,
                                                               @PathVariable int versionNumber,
                                                               HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(MemoryResponse.fromEntity(
                memoryService.restoreVersion(id, versionNumber, userId))));
    }

    @GetMapping("/{id}/fragments")
    public ResponseEntity<ApiResponse<List<MemoryFragmentResponse>>> listFragments(@PathVariable String id,
                                                                            HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        List<MemoryFragmentResponse> fragments = memoryService.getFragments(id, userId).stream()
                .map(MemoryFragmentResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(fragments));
    }

    /**
     * 重建场景：删除现有 fragments，重新调 ai-service 生成 grounded 内容。
     * 用户在记忆详情页一键触发，让历史"假" fragment（旧规则版套模板）刷成
     * 紧扣描述的真实碎片。
     */
    @PostMapping("/{id}/regenerate-scene")
    public ResponseEntity<ApiResponse<MemoryResponse>> regenerateScene(@PathVariable String id,
                                                                       HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(MemoryResponse.fromEntity(
                memoryService.regenerateScene(id, userId))));
    }

    /**
     * 跨用户公共记忆池（专供 resonance-service 真实化检索；非前端直调）。
     *
     * <p>返回他人 PRIVACY_LEVEL = PUBLIC 的最近 N 条记忆，排除调用方自己。
     * 不脱敏 ownerId / title / description —— 这是 PUBLIC 设计应有的语义；
     * 真正面向前端的脱敏由 atlas/others 端点专门负责。
     *
     * <p>权限：要求登录（任何已认证用户都能拉公共池），通过 X-User-Id 取 caller。
     */
    @GetMapping("/public-pool")
    public ResponseEntity<ApiResponse<List<MemoryResponse>>> publicPool(
            @RequestParam(defaultValue = "200") int limit,
            HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        int safeLimit = Math.max(10, Math.min(limit, 500));
        List<Memory> rows = memoryRepository.findPublicPoolExcludingUser(
                userId, org.springframework.data.domain.PageRequest.of(0, safeLimit));
        List<MemoryResponse> items = rows.stream()
                .map(MemoryResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @PostMapping("/fragments/{fragmentId}/discover")
    public ResponseEntity<ApiResponse<MemoryFragmentResponse>> discoverFragment(@PathVariable String fragmentId,
                                                                          HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(MemoryFragmentResponse.fromEntity(
                memoryService.discoverFragment(fragmentId, userId))));
    }
}
