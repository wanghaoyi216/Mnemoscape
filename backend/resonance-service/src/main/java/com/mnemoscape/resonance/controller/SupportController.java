package com.mnemoscape.resonance.controller;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.dto.PageResult;
import com.mnemoscape.common.web.RequestContext;
import com.mnemoscape.resonance.model.entity.SupportMessage;
import com.mnemoscape.resonance.model.entity.SupportTicket;
import com.mnemoscape.resonance.service.SupportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户端客服工单接口（用户提交问题、查看自己的工单、收发消息）。
 *
 * <p>所有接口都通过 X-User-Id 头识别调用方（gateway 注入）。管理员视角的接口在
 * {@code AdminSupportController} 下，路径前缀 /api/v1/admin/support。
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final SupportService supportService;

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    /** 创建工单。body: {subject, description, priority?, clientType?} */
    @PostMapping("/tickets")
    public ResponseEntity<ApiResponse<SupportTicket>> createTicket(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        String subject = (String) body.get("subject");
        String description = (String) body.get("description");
        String priority = (String) body.get("priority");
        String clientType = (String) body.get("clientType");
        SupportTicket t = supportService.createTicket(userId, subject, description, priority, clientType);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(t));
    }

    /** 查看自己的工单列表（分页）。 */
    @GetMapping("/tickets")
    public ResponseEntity<ApiResponse<PageResult<SupportTicket>>> myTickets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        Page<SupportTicket> p = supportService.listMyTickets(userId, page, size, status);
        return ResponseEntity.ok(ApiResponse.success(
                PageResult.of(p.getContent(), p.getTotalElements(), page, size)));
    }

    @GetMapping("/tickets/{ticketId}")
    public ResponseEntity<ApiResponse<SupportTicket>> getTicket(
            @PathVariable String ticketId,
            HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        boolean isAdmin = "ADMIN".equals(req.getHeader("X-User-Role"));
        return ResponseEntity.ok(ApiResponse.success(
                supportService.getTicket(ticketId, userId, isAdmin)));
    }

    @GetMapping("/tickets/{ticketId}/messages")
    public ResponseEntity<ApiResponse<List<SupportMessage>>> listMessages(
            @PathVariable String ticketId,
            HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        boolean isAdmin = "ADMIN".equals(req.getHeader("X-User-Role"));
        List<SupportMessage> msgs = supportService.listMessages(ticketId, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success(msgs));
    }

    /**
     * 发送消息。body: {content, messageType?, fileName?, fileSize?}
     * messageType ∈ {TEXT, IMAGE, FILE, EMOJI}
     */
    @PostMapping("/tickets/{ticketId}/messages")
    public ResponseEntity<ApiResponse<SupportMessage>> sendMessage(
            @PathVariable String ticketId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        String role = req.getHeader("X-User-Role");
        boolean isAdmin = "ADMIN".equals(role);
        String content = (String) body.get("content");
        String messageType = (String) body.get("messageType");
        String fileName = (String) body.get("fileName");
        Long fileSize = body.get("fileSize") instanceof Number n ? n.longValue() : null;
        SupportMessage m = supportService.sendMessage(
                ticketId, userId, isAdmin ? "ADMIN" : "USER",
                content, messageType, fileName, fileSize);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(m));
    }

    @PostMapping("/tickets/{ticketId}/close")
    public ResponseEntity<ApiResponse<SupportTicket>> closeTicket(
            @PathVariable String ticketId,
            HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        boolean isAdmin = "ADMIN".equals(req.getHeader("X-User-Role"));
        return ResponseEntity.ok(ApiResponse.success(
                supportService.closeTicket(ticketId, userId, isAdmin)));
    }

    @PostMapping("/tickets/{ticketId}/read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markRead(
            @PathVariable String ticketId,
            HttpServletRequest req) {
        String userId = RequestContext.requireUserId(req);
        boolean isAdmin = "ADMIN".equals(req.getHeader("X-User-Role"));
        int updated = supportService.markRead(ticketId, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated", updated)));
    }
}
