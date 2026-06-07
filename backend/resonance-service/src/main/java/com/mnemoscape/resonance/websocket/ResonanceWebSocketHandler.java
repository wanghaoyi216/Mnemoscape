package com.mnemoscape.resonance.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResonanceWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ResonanceWebSocketHandler.class);
    private static final String ATTR_RESONANCE_ID = "resonanceId";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_POSITION = "position";
    private static final String ATTR_LOOKING_AT = "lookingAt";
    private static final String ATTR_CLEANED_UP = "cleanedUp";
    private static final List<Double> DEFAULT_POSITION = List.of(0.0, 1.8, 0.0);
    private static final Map<String, Double> DEFAULT_LOOKING_AT = Map.of("x", 0.0, "y", 0.0, "z", -1.0);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Set<WebSocketSession>> resonanceRooms = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessionRegistry = new ConcurrentHashMap<>();

    private enum MessageType {
        JOIN, LEAVE, MOVE, PLACE_NOTE;

        static Optional<MessageType> from(String raw) {
            if (raw == null) return Optional.empty();
            try {
                return Optional.of(MessageType.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
    }

    private static class BaseMessage {
        public String type;
        public String resonanceId;
    }

    private static class JoinMessage extends BaseMessage {
        public String userId;
        public List<Object> position;
        public Map<String, Object> lookingAt;
    }

    private static class MoveMessage extends BaseMessage {
        public List<Object> position;
        public Map<String, Object> lookingAt;
    }

    private static class PlaceNoteMessage extends BaseMessage {
        public String content;
        public String mood;
        public List<Object> position;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = session.getId();
        sessionRegistry.put(sessionId, session);
        log.info("WebSocket connected: {}", sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            BaseMessage base = objectMapper.treeToValue(payload, BaseMessage.class);
            if (base == null || base.type == null) {
                sendError(session, "INVALID_MESSAGE", "Missing message type.");
                return;
            }
            Optional<MessageType> messageType = MessageType.from(base.type);
            if (messageType.isEmpty()) {
                log.warn("Unknown message type: {} from {}", base.type, session.getId());
                sendError(session, "UNKNOWN_TYPE", "Unknown message type.");
                return;
            }
            switch (messageType.get()) {
                case JOIN -> handleJoin(session, objectMapper.treeToValue(payload, JoinMessage.class));
                case LEAVE -> handleLeave(session, base.resonanceId);
                case MOVE -> handleMove(session, objectMapper.treeToValue(payload, MoveMessage.class));
                case PLACE_NOTE -> handlePlaceNote(session, objectMapper.treeToValue(payload, PlaceNoteMessage.class));
            }
        } catch (Exception e) {
            log.error("Failed to handle message from {}: {}", session.getId(), message.getPayload(), e);
            sendError(session, "INVALID_MESSAGE", "Malformed message payload.");
        }
    }

    private void handleJoin(WebSocketSession session, JoinMessage msg) throws IOException {
        if (msg == null || !ensureResonanceId(session, msg.resonanceId)) {
            return;
        }
        String resonanceId = msg.resonanceId;
        String userId = (msg.userId != null && !msg.userId.isBlank()) ? msg.userId : "anonymous";
        List<Double> position = Optional.ofNullable(normalizePosition(msg.position)).orElse(DEFAULT_POSITION);
        Map<String, Double> lookingAt = Optional.ofNullable(normalizeLookingAt(msg.lookingAt)).orElse(DEFAULT_LOOKING_AT);

        String previousResonance = (String) session.getAttributes().get(ATTR_RESONANCE_ID);
        if (previousResonance != null && !previousResonance.equals(resonanceId)) {
            removeFromRoom(previousResonance, session, true);
        }

        resonanceRooms.computeIfAbsent(resonanceId, k -> ConcurrentHashMap.newKeySet()).add(session);
        session.getAttributes().put(ATTR_RESONANCE_ID, resonanceId);
        session.getAttributes().put(ATTR_USER_ID, userId);
        session.getAttributes().put(ATTR_POSITION, position);
        session.getAttributes().put(ATTR_LOOKING_AT, lookingAt);

        sendExistingGhosts(session, resonanceId);

        Map<String, Object> joinMsg = new LinkedHashMap<>();
        joinMsg.put("type", "GHOST_JOIN");
        joinMsg.put("userId", userId);
        joinMsg.put("position", position);
        joinMsg.put("lookingAt", lookingAt);
        broadcastToRoom(resonanceId, joinMsg, session);
    }

    private void handleLeave(WebSocketSession session, String resonanceId) throws IOException {
        String activeResonance = resolveResonanceId(session, resonanceId);
        if (activeResonance == null) {
            log.warn("Leave requested without resonanceId from {}", session.getId());
            return;
        }
        if (!ensureJoined(session, activeResonance)) {
            return;
        }
        removeFromRoom(activeResonance, session, true);
        session.getAttributes().remove(ATTR_RESONANCE_ID);
    }

    private void handleMove(WebSocketSession session, MoveMessage msg) throws IOException {
        if (msg == null || !ensureResonanceId(session, msg.resonanceId)) {
            return;
        }
        if (!ensureJoined(session, msg.resonanceId)) {
            return;
        }
        List<Double> position = normalizePosition(msg.position);
        if (position == null) {
            sendError(session, "INVALID_POSITION", "Move requires a position vector.");
            return;
        }
        Map<String, Double> lookingAt = Optional.ofNullable(normalizeLookingAt(msg.lookingAt)).orElse(getLookingAt(session));
        session.getAttributes().put(ATTR_POSITION, position);
        session.getAttributes().put(ATTR_LOOKING_AT, lookingAt);

        Map<String, Object> moveMsg = new LinkedHashMap<>();
        moveMsg.put("type", "GHOST_MOVE");
        moveMsg.put("userId", session.getAttributes().get(ATTR_USER_ID));
        moveMsg.put("position", position);
        moveMsg.put("lookingAt", lookingAt);
        broadcastToRoom(msg.resonanceId, moveMsg, session);
    }

    private void handlePlaceNote(WebSocketSession session, PlaceNoteMessage msg) throws IOException {
        if (msg == null || !ensureResonanceId(session, msg.resonanceId)) {
            return;
        }
        if (!ensureJoined(session, msg.resonanceId)) {
            return;
        }
        Map<String, Object> noteMsg = new LinkedHashMap<>();
        noteMsg.put("type", "NOTE_PLACED");
        noteMsg.put("resonanceId", msg.resonanceId);
        noteMsg.put("userId", session.getAttributes().get(ATTR_USER_ID));
        if (msg.content != null) {
            noteMsg.put("content", msg.content);
        }
        if (msg.mood != null) {
            noteMsg.put("mood", msg.mood);
        }
        List<Double> position = normalizePosition(msg.position);
        if (position != null) {
            noteMsg.put("position", position);
        }
        broadcastToRoom(msg.resonanceId, noteMsg, null);
    }

    private void broadcastToRoom(String resonanceId, Map<String, Object> msg, WebSocketSession exclude) throws IOException {
        if (resonanceId == null) {
            log.warn("Broadcast skipped without resonanceId for type {}", msg.get("type"));
            return;
        }
        Set<WebSocketSession> room = resonanceRooms.get(resonanceId);
        if (room == null || room.isEmpty()) {
            return;
        }

        String json = objectMapper.writeValueAsString(msg);
        TextMessage textMsg = new TextMessage(json);
        List<WebSocketSession> staleSessions = new ArrayList<>();
        for (WebSocketSession s : room) {
            if (s != exclude && s.isOpen()) {
                try {
                    s.sendMessage(textMsg);
                } catch (IOException e) {
                    log.error("Failed to send to session {}", s.getId(), e);
                    staleSessions.add(s);
                }
            } else if (s != exclude) {
                staleSessions.add(s);
            }
        }
        for (WebSocketSession stale : staleSessions) {
            removeFromRoom(resonanceId, stale, true);
            closeSessionQuietly(stale);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanupSession(session, true);
        log.info("WebSocket disconnected: {} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Transport error on session {}", session.getId(), exception);
        closeSessionQuietly(session);
        cleanupSession(session, true);
    }

    private void cleanupSession(WebSocketSession session, boolean notify) {
        if (Boolean.TRUE.equals(session.getAttributes().get(ATTR_CLEANED_UP))) {
            return;
        }
        session.getAttributes().put(ATTR_CLEANED_UP, true);
        List<String> resonanceIds = new ArrayList<>();
        String activeResonance = (String) session.getAttributes().get(ATTR_RESONANCE_ID);
        if (activeResonance != null) {
            resonanceIds.add(activeResonance);
        } else {
            resonanceIds.addAll(findResonanceIdsForSession(session));
        }
        for (String resonanceId : resonanceIds) {
            try {
                removeFromRoom(resonanceId, session, notify);
            } catch (IOException e) {
                log.error("Error cleaning up session {} in resonance {}", session.getId(), resonanceId, e);
            }
        }
        sessionRegistry.remove(session.getId());
        session.getAttributes().remove(ATTR_RESONANCE_ID);
    }

    private List<String> findResonanceIdsForSession(WebSocketSession session) {
        List<String> resonanceIds = new ArrayList<>();
        for (Map.Entry<String, Set<WebSocketSession>> entry : resonanceRooms.entrySet()) {
            if (entry.getValue().contains(session)) {
                resonanceIds.add(entry.getKey());
            }
        }
        return resonanceIds;
    }

    private void removeFromRoom(String resonanceId, WebSocketSession session, boolean notify) throws IOException {
        Set<WebSocketSession> room = resonanceRooms.get(resonanceId);
        boolean removed = false;
        if (room != null) {
            removed = room.remove(session);
            if (room.isEmpty()) {
                resonanceRooms.remove(resonanceId);
            }
        }
        if (notify && removed) {
            sendGhostLeft(resonanceId, session);
        }
    }

    private void sendGhostLeft(String resonanceId, WebSocketSession session) throws IOException {
        Object userId = session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            return;
        }
        Map<String, Object> leaveMsg = Map.of(
                "type", "GHOST_LEFT",
                "userId", userId
        );
        broadcastToRoom(resonanceId, leaveMsg, session);
    }

    private void sendExistingGhosts(WebSocketSession session, String resonanceId) throws IOException {
        Set<WebSocketSession> room = resonanceRooms.get(resonanceId);
        if (room == null) {
            return;
        }
        for (WebSocketSession existing : room) {
            if (existing == session) {
                continue;
            }
            Object userId = existing.getAttributes().get(ATTR_USER_ID);
            if (userId == null) {
                continue;
            }
            Map<String, Object> joinMsg = new LinkedHashMap<>();
            joinMsg.put("type", "GHOST_JOIN");
            joinMsg.put("userId", userId);
            joinMsg.put("position", getPosition(existing));
            joinMsg.put("lookingAt", getLookingAt(existing));
            sendMessage(session, joinMsg);
        }
    }

    private boolean ensureResonanceId(WebSocketSession session, String resonanceId) throws IOException {
        if (resonanceId == null || resonanceId.isBlank()) {
            sendError(session, "MISSING_RESONANCE", "Resonance id is required.");
            return false;
        }
        return true;
    }

    private boolean ensureJoined(WebSocketSession session, String resonanceId) throws IOException {
        String activeResonance = (String) session.getAttributes().get(ATTR_RESONANCE_ID);
        if (activeResonance == null || !activeResonance.equals(resonanceId)) {
            sendError(session, "NOT_JOINED", "Join the resonance space before sending updates.");
            return false;
        }
        return true;
    }

    private String resolveResonanceId(WebSocketSession session, String resonanceId) {
        if (resonanceId != null && !resonanceId.isBlank()) {
            return resonanceId;
        }
        return (String) session.getAttributes().get(ATTR_RESONANCE_ID);
    }

    private List<Double> normalizePosition(List<Object> raw) {
        if (raw == null || raw.size() < 3) {
            return null;
        }
        List<Double> position = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Object value = raw.get(i);
            if (!(value instanceof Number number)) {
                return null;
            }
            position.add(number.doubleValue());
        }
        return position;
    }

    private Map<String, Double> normalizeLookingAt(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Double x = toDouble(raw.get("x"));
        Double y = toDouble(raw.get("y"));
        Double z = toDouble(raw.get("z"));
        if (x == null || y == null || z == null) {
            return null;
        }
        return Map.of("x", x, "y", y, "z", z);
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private List<Double> getPosition(WebSocketSession session) {
        Object position = session.getAttributes().get(ATTR_POSITION);
        if (position instanceof List<?> list) {
            List<Double> normalized = normalizePosition(new ArrayList<>(list));
            if (normalized != null) {
                return normalized;
            }
        }
        return DEFAULT_POSITION;
    }

    private Map<String, Double> getLookingAt(WebSocketSession session) {
        Object lookingAt = session.getAttributes().get(ATTR_LOOKING_AT);
        if (lookingAt instanceof Map<?, ?> map) {
            Map<String, Object> raw = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    raw.put(key, entry.getValue());
                }
            }
            Map<String, Double> normalized = normalizeLookingAt(raw);
            if (normalized != null) {
                return normalized;
            }
        }
        return DEFAULT_LOOKING_AT;
    }

    private void sendError(WebSocketSession session, String code, String message) throws IOException {
        Map<String, Object> errorMsg = Map.of(
                "type", "ERROR",
                "code", code,
                "message", message
        );
        sendMessage(session, errorMsg);
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> msg) throws IOException {
        if (!session.isOpen()) {
            return;
        }
        String json = objectMapper.writeValueAsString(msg);
        session.sendMessage(new TextMessage(json));
    }

    private void closeSessionQuietly(WebSocketSession session) {
        if (session.isOpen()) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException e) {
                log.error("Failed to close session {}", session.getId(), e);
            }
        }
    }
}
