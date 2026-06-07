package com.mnemoscape.asset.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 静态资源热更新推送通道。
 *
 * <p>{@link com.mnemoscape.asset.service.LocalResourceWatcher} 检测到
 * {@code resource/} 目录文件变动并重扫后，调用 {@link #broadcastResourceChanged(int)}
 * 把一帧 {@code {type:"RESOURCE_CHANGED", total:N}} 推给所有连着的前端。
 * 前端监听后调用 {@code useDynamicMedia().refresh()} 即可在不刷新页面的前提下
 * 更新封面选择器等组件的内置素材库。
 *
 * <p>这是一个"通知"通道而非"数据"通道：只告诉前端"变了"，让前端走既有 REST
 * 端点重新拉全量，避免在 WS 里搬运大列表。
 */
@Component
public class ResourceWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ResourceWebSocketHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("[ResourceWS] connected session={} (total={})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("[ResourceWS] disconnected session={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[ResourceWS] transport error session={}: {}", session.getId(), exception.toString());
        sessions.remove(session);
    }

    /** 广播资源变更通知。best-effort：单 session 发送失败不影响其它。 */
    public void broadcastResourceChanged(int total) {
        if (sessions.isEmpty()) return;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "type", "RESOURCE_CHANGED",
                    "total", total,
                    "timestamp", System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("[ResourceWS] failed to serialize notification: {}", e.toString());
            return;
        }
        TextMessage msg = new TextMessage(payload);
        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(msg);
                    }
                }
            } catch (Exception e) {
                log.warn("[ResourceWS] failed to push to session={}: {}", s.getId(), e.toString());
            }
        }
        log.info("[ResourceWS] broadcast RESOURCE_CHANGED total={} to {} sessions", total, sessions.size());
    }
}
