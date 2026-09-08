package com.mnemoscape.resonance.service;

import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.resonance.model.entity.SupportMessage;
import com.mnemoscape.resonance.model.entity.SupportTicket;
import com.mnemoscape.resonance.repository.SupportMessageRepository;
import com.mnemoscape.resonance.repository.SupportTicketRepository;
import com.mnemoscape.resonance.websocket.SupportWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 客服工单业务服务。
 *
 * <p>用户端动作：创建工单、发送消息、查看自己的工单列表、关闭工单。
 * 管理端动作：查看所有工单（分页 + 过滤）、回复消息、改状态 / 优先级 / 指派、删除。
 *
 * <p>每次写入消息后通过 {@link SupportWebSocketHandler} 推送 WebSocket 通知给关注方
 * （用户工单：推给 owner；管理员视角：推给所有在 ADMIN 频道的连接）—— 失败不阻塞主流程。
 */
@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);

    private final SupportTicketRepository ticketRepository;
    private final SupportMessageRepository messageRepository;

    /** WebSocket 推送 — lazy 注入避免循环依赖（handler 也注入 service）。 */
    @Autowired(required = false)
    @Lazy
    private SupportWebSocketHandler wsHandler;

    public SupportService(SupportTicketRepository ticketRepository,
                          SupportMessageRepository messageRepository) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
    }

    // -------- USER: 工单 CRUD --------

    @Transactional
    public SupportTicket createTicket(String userId, String subject, String description,
                                       String priority, String clientType) {
        if (userId == null || userId.isBlank()) throw new BizException(401, "AUTH_REQUIRED");
        if (subject == null || subject.isBlank()) throw new BizException(400, "SUBJECT_REQUIRED");

        SupportTicket t = new SupportTicket();
        t.setUserId(userId);
        t.setSubject(subject.trim());
        t.setDescription(description == null ? "" : description.trim());
        t.setPriority(normalizePriority(priority));
        t.setClientType(clientType);
        t.setStatus("OPEN");
        ticketRepository.save(t);

        // 记一条系统首条消息，便于工单页面有初始内容
        if (!t.getDescription().isEmpty()) {
            SupportMessage init = new SupportMessage();
            init.setTicketId(t.getId());
            init.setSenderId(userId);
            init.setSenderRole("USER");
            init.setContent(t.getDescription());
            init.setMessageType("TEXT");
            messageRepository.save(init);
        }
        // 通知所有在线 ADMIN：有新工单
        notifyAdmins("ticket_created", t);

        return t;
    }

    public Page<SupportTicket> listMyTickets(String userId, int page, int size, String status) {
        int safeSize = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, page);
        Sort sort = Sort.by("lastMessageAt").descending();
        PageRequest pr = PageRequest.of(safePage, safeSize, sort);
        if (status != null && !status.isBlank()) {
            String st = status.trim().toUpperCase();
            Specification<SupportTicket> spec = (root, q, cb) -> cb.and(
                    cb.equal(root.get("userId"), userId),
                    cb.equal(root.get("status"), st)
            );
            return ticketRepository.findAll(spec, pr);
        }
        return ticketRepository.findByUserIdOrderByLastMessageAtDesc(userId, pr);
    }

    public SupportTicket getTicket(String ticketId, String callerUserId, boolean isAdmin) {
        SupportTicket t = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException(404, "TICKET_NOT_FOUND"));
        if (!isAdmin && !t.getUserId().equals(callerUserId)) {
            throw new BizException(403, "FORBIDDEN");
        }
        return t;
    }

    public List<SupportMessage> listMessages(String ticketId, String callerUserId, boolean isAdmin) {
        getTicket(ticketId, callerUserId, isAdmin); // 权限校验
        return messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    @Transactional
    public SupportMessage sendMessage(String ticketId, String senderId, String senderRole,
                                       String content, String messageType,
                                       String fileName, Long fileSize) {
        SupportTicket t = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException(404, "TICKET_NOT_FOUND"));
        boolean isAdmin = "ADMIN".equals(senderRole);
        if (!isAdmin && !t.getUserId().equals(senderId)) {
            throw new BizException(403, "FORBIDDEN");
        }
        if ("CLOSED".equals(t.getStatus())) {
            throw new BizException(400, "TICKET_CLOSED");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(400, "CONTENT_REQUIRED");
        }
        SupportMessage m = new SupportMessage();
        m.setTicketId(ticketId);
        m.setSenderId(senderId);
        m.setSenderRole(isAdmin ? "ADMIN" : "USER");
        m.setContent(content);
        m.setMessageType(messageType == null || messageType.isBlank() ? "TEXT" : messageType.trim().toUpperCase());
        m.setFileName(fileName);
        m.setFileSize(fileSize);
        messageRepository.save(m);

        // 状态机：管理员首次回复时把状态从 OPEN → IN_PROGRESS
        if (isAdmin && "OPEN".equals(t.getStatus())) {
            t.setStatus("IN_PROGRESS");
            if (t.getAssignedAdminId() == null) {
                t.setAssignedAdminId(senderId);
            }
        }
        t.setLastMessageAt(LocalDateTime.now());
        ticketRepository.save(t);

        // 推送给关心方
        if (isAdmin) {
            // 管理员发的消息：通知 owner
            notifyUser(t.getUserId(), "message_new", m);
        } else {
            // 用户发的消息：通知所有 ADMIN
            notifyAdmins("message_new", m);
        }

        return m;
    }

    @Transactional
    public SupportTicket closeTicket(String ticketId, String callerUserId, boolean isAdmin) {
        SupportTicket t = getTicket(ticketId, callerUserId, isAdmin);
        t.setStatus("CLOSED");
        t.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(t);
        notifyUser(t.getUserId(), "ticket_status", t);
        notifyAdmins("ticket_status", t);
        return t;
    }

    // -------- ADMIN ONLY --------

    public Page<SupportTicket> adminListTickets(int page, int size,
                                                 String status, String priority,
                                                 String userId, String search,
                                                 String assignedAdminId) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        Sort sort = Sort.by("lastMessageAt").descending();
        Specification<SupportTicket> spec = (root, q, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase();
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), s));
        }
        if (priority != null && !priority.isBlank()) {
            String p = priority.trim().toUpperCase();
            spec = spec.and((root, q, cb) -> cb.equal(root.get("priority"), p));
        }
        if (userId != null && !userId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("userId"), userId.trim()));
        }
        if (assignedAdminId != null && !assignedAdminId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("assignedAdminId"), assignedAdminId.trim()));
        }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("subject")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern)
            ));
        }
        return ticketRepository.findAll(spec, PageRequest.of(safePage, safeSize, sort));
    }

    @Transactional
    public SupportTicket adminUpdate(String ticketId, String status, String priority,
                                      String assignedAdminId, String adminUserId) {
        SupportTicket t = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException(404, "TICKET_NOT_FOUND"));
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase();
            if (!s.equals("OPEN") && !s.equals("IN_PROGRESS") && !s.equals("RESOLVED") && !s.equals("CLOSED")) {
                throw new BizException(400, "INVALID_STATUS");
            }
            t.setStatus(s);
        }
        if (priority != null && !priority.isBlank()) {
            t.setPriority(normalizePriority(priority));
        }
        if (assignedAdminId != null) {
            t.setAssignedAdminId(assignedAdminId.isBlank() ? null : assignedAdminId.trim());
        } else if (t.getAssignedAdminId() == null && adminUserId != null) {
            // 默认指派给当前操作的管理员
            t.setAssignedAdminId(adminUserId);
        }
        t.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(t);
        notifyUser(t.getUserId(), "ticket_status", t);
        return t;
    }

    @Transactional
    public void adminDelete(String ticketId) {
        SupportTicket t = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException(404, "TICKET_NOT_FOUND"));
        try {
            messageRepository.deleteByTicketId(ticketId);
        } catch (Exception e) {
            log.warn("[support] delete messages failed for ticket {}: {}", ticketId, e.toString());
        }
        ticketRepository.delete(t);
    }

    @Transactional
    public int markRead(String ticketId, String callerUserId, boolean isAdmin) {
        SupportTicket t = getTicket(ticketId, callerUserId, isAdmin);
        // 用户视角：标记 admin-sent 消息为已读
        // 管理员视角：标记 user-sent 消息为已读
        String otherRole = isAdmin ? "USER" : "ADMIN";
        var msgs = messageRepository.findByTicketIdOrderByCreatedAtAsc(t.getId());
        int updated = 0;
        LocalDateTime now = LocalDateTime.now();
        java.util.List<SupportMessage> toUpdate = new java.util.ArrayList<>();
        for (SupportMessage m : msgs) {
            if (otherRole.equals(m.getSenderRole()) && m.getReadAt() == null) {
                m.setReadAt(now);
                toUpdate.add(m);
                updated++;
            }
        }
        if (!toUpdate.isEmpty()) {
            messageRepository.saveAll(toUpdate);
        }
        return updated;
    }

    private String normalizePriority(String p) {
        if (p == null || p.isBlank()) return "NORMAL";
        String up = p.trim().toUpperCase();
        if (!up.equals("LOW") && !up.equals("NORMAL") && !up.equals("HIGH") && !up.equals("URGENT")) {
            return "NORMAL";
        }
        return up;
    }

    private void notifyUser(String userId, String type, Object payload) {
        if (wsHandler == null || userId == null) return;
        try {
            wsHandler.broadcastToUser(userId, type, payload);
        } catch (Exception e) {
            log.warn("[support] notifyUser failed: {}", e.toString());
        }
    }

    private void notifyAdmins(String type, Object payload) {
        if (wsHandler == null) return;
        try {
            wsHandler.broadcastToAdmins(type, payload);
        } catch (Exception e) {
            log.warn("[support] notifyAdmins failed: {}", e.toString());
        }
    }
}
