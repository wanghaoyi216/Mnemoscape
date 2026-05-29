package com.mnemoscape.resonance.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客服系统 WebSocket。
 *
 * <p>连接形态：
 * <ul>
 *   <li>用户连：{@code /ws/support?userId=<id>&role=USER} —— 收到自己的工单更新与管理员回复</li>
 *   <li>管理员连：{@code /ws/support?userId=<adminId>&role=ADMIN} —— 收到所有新工单 / 新消息</li>
 * </ul>
 *
 * <p>这层只负责"广播通道"，业务写入由 SupportService 主导，写完后调
 * {@link #broadcastToUser(String, String, Object)} / {@link #broadcastToAdmins(String, Object)}
 * 推送实时事件。前端 reactive 收到事件后即时刷新工单列表 / 消息流。
 */
@Component
public class SupportWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SupportWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** userId → sessions（USER 角色） */
    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    /** adminId → sessions（ADMIN 角色） */
    private final Map<String, Set<WebSocketSession>> adminSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = queryParam(session, "userId");
        String role = queryParam(session, "role");
        if (userId == null || userId.isBlank()) {
            log.warn("Support WS connected without userId");
            try { session.close(CloseStatus.BAD_DATA); } catch (IOException ignored) {}
            return;
        }
        boolean isAdmin = "ADMIN".equalsIgnoreCase(role);
        session.getAttributes().put("userId", userId);
        session.getAttributes().put("role", isAdmin ? "ADMIN" : "USER");
        Map<String, Set<WebSocketSession>> bucket = isAdmin ? adminSessions : userSessions;
        bucket.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("Support WS connected userId={} role={} session={}", userId, isAdmin ? "ADMIN" : "USER", session.getId());

        // 客户端可以直接发 ping / mark 读等指令；目前不需要双向逻辑，留作扩展点
        try {
            sendJson(session, Map.of("type", "CONNECTED", "role", isAdmin ? "ADMIN" : "USER"));
        } catch (Exception ignored) {}
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Support WS transport error session={} reason={}", session.getId(), exception.toString());
        removeSession(session);
        try { if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {}
    }

    private void removeSession(WebSocketSession session) {
        String userId = (String) session.getAttributes().get("userId");
        String role = (String) session.getAttributes().get("role");
        if (userId == null) return;
        Map<String, Set<WebSocketSession>> bucket = "ADMIN".equals(role) ? adminSessions : userSessions;
        Set<WebSocketSession> set = bucket.get(userId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) bucket.remove(userId);
        }
    }

    public void broadcastToUser(String userId, String type, Object payload) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("payload", payload);
        sendToAll(sessions, msg);
    }

    public void broadcastToAdmins(String type, Object payload) {
        if (adminSessions.isEmpty()) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("payload", payload);
        for (Set<WebSocketSession> sessions : adminSessions.values()) {
            sendToAll(sessions, msg);
        }
    }

    private void sendToAll(Set<WebSocketSession> sessions, Map<String, Object> msg) {
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.warn("Support WS serialize failed: {}", e.toString());
            return;
        }
        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) continue;
            try {
                s.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.debug("Support WS send failed session={}: {}", s.getId(), e.toString());
            }
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> msg) throws IOException {
        if (!session.isOpen()) return;
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
    }

    private String queryParam(WebSocketSession session, String key) {
        String query = session.getUri() == null ? null : session.getUri().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
