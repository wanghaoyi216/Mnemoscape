package com.mnemoscape.resonance.admin;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.dto.PageResult;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.resonance.model.entity.SupportTicket;
import com.mnemoscape.resonance.repository.SupportTicketRepository;
import com.mnemoscape.resonance.service.SupportService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员端客服工单接口。
 */
@RestController
@RequestMapping("/api/v1/admin/support")
public class AdminSupportController {

    private static final Logger audit = LoggerFactory.getLogger("admin-audit");

    private final SupportService supportService;
    private final SupportTicketRepository ticketRepository;

    public AdminSupportController(SupportService supportService,
                                   SupportTicketRepository ticketRepository) {
        this.supportService = supportService;
        this.ticketRepository = ticketRepository;
    }

    @GetMapping("/tickets")
    public ResponseEntity<ApiResponse<PageResult<SupportTicket>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String assignedAdminId,
            HttpServletRequest req) {
        Page<SupportTicket> p = supportService.adminListTickets(
                page, size, status, priority, userId, search, assignedAdminId);
        logAccess(req, "/api/v1/admin/support/tickets", "list", 200);
        return ResponseEntity.ok(ApiResponse.success(
                PageResult.of(p.getContent(), p.getTotalElements(), page, size)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats(HttpServletRequest req) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("open", ticketRepository.countByStatus("OPEN"));
        stats.put("inProgress", ticketRepository.countByStatus("IN_PROGRESS"));
        stats.put("resolved", ticketRepository.countByStatus("RESOLVED"));
        stats.put("closed", ticketRepository.countByStatus("CLOSED"));
        stats.put("total", ticketRepository.count());
        logAccess(req, "/api/v1/admin/support/stats", "stats", 200);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    /** 更新工单状态 / 优先级 / 指派。body 字段全部 optional。 */
    @PatchMapping("/tickets/{ticketId}")
    public ResponseEntity<ApiResponse<SupportTicket>> update(
            @PathVariable String ticketId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String adminUserId = req.getHeader("X-User-Id");
        SupportTicket t = supportService.adminUpdate(
                ticketId,
                (String) body.get("status"),
                (String) body.get("priority"),
                (String) body.get("assignedAdminId"),
                adminUserId);
        logAccess(req, "/api/v1/admin/support/tickets/" + ticketId, "patched", 200);
        return ResponseEntity.ok(ApiResponse.success(t));
    }

    @DeleteMapping("/tickets/{ticketId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String ticketId,
            HttpServletRequest req) {
        supportService.adminDelete(ticketId);
        logAccess(req, "/api/v1/admin/support/tickets/" + ticketId, "deleted", 200);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }

    /** 批量更新状态。body: {ids: [...], status: "RESOLVED"} */
    @PostMapping("/tickets/batch-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchStatus(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        String status = (String) body.get("status");
        if (ids == null || ids.isEmpty()) throw new BizException(400, "IDS_REQUIRED");
        if (status == null || status.isBlank()) throw new BizException(400, "STATUS_REQUIRED");
        if (ids.size() > 200) throw new BizException(400, "BATCH_TOO_LARGE");
        int updated = 0;
        String adminUserId = req.getHeader("X-User-Id");
        for (String id : ids) {
            try {
                supportService.adminUpdate(id, status, null, null, adminUserId);
                updated++;
            } catch (Exception ignore) {
                // skip failed
            }
        }
        logAccess(req, "/api/v1/admin/support/tickets/batch-status",
                "updated=" + updated, 200);
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated", updated)));
    }

    private void logAccess(HttpServletRequest req, String path, String detail, int status) {
        try {
            String adminUserId = req.getHeader("X-User-Id");
            audit.info("admin-support adminUserId={} path={} detail={} status={}",
                    adminUserId, path, detail, status);
        } catch (Exception ignore) {}
    }
}
