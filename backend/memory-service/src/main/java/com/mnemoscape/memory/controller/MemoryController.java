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

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
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

    @PostMapping("/fragments/{fragmentId}/discover")
    public ResponseEntity<ApiResponse<MemoryFragmentResponse>> discoverFragment(@PathVariable String fragmentId,
                                                                          HttpServletRequest httpReq) {
        String userId = RequestContext.requireUserId(httpReq);
        return ResponseEntity.ok(ApiResponse.success(MemoryFragmentResponse.fromEntity(
                memoryService.discoverFragment(fragmentId, userId))));
    }
}
