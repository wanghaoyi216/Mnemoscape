package com.mnemoscape.resonance.websocket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.resonance.model.entity.ChatGroup;
import com.mnemoscape.resonance.model.entity.ChatGroupMember;
import com.mnemoscape.resonance.model.entity.ChatMessage;
import com.mnemoscape.resonance.repository.ChatGroupMemberRepository;
import com.mnemoscape.resonance.repository.ChatGroupRepository;
import com.mnemoscape.resonance.repository.ChatMessageRepository;
import com.mnemoscape.resonance.service.ChatAiAssistant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatMessageRepository chatMessageRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final ChatGroupMemberRepository chatGroupMemberRepository;
    private final ChatAiAssistant aiAssistant;

    // Registry: userId -> Set of active WebSocketSessions
    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    /** 群聊 @AI 异步生成线程池 —— LLM 调用 5-30s，绝不能阻塞 WS 消息线程。 */
    private final java.util.concurrent.ExecutorService aiExec =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "chat-ai-assistant");
                t.setDaemon(true);
                return t;
            });

    public ChatWebSocketHandler(ChatMessageRepository chatMessageRepository,
                                 ChatGroupRepository chatGroupRepository,
                                 ChatGroupMemberRepository chatGroupMemberRepository,
                                 ChatAiAssistant aiAssistant) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.chatGroupMemberRepository = chatGroupMemberRepository;
        this.aiAssistant = aiAssistant;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ChatPayload {
        public String type; // SEND_MSG, GET_HISTORY, CREATE_GROUP, AUTH
        public String userId;
        public String receiverId;
        public String groupId;
        public String content;
        public String messageType; // TEXT, IMAGE, FILE
        public String fileName;
        public Long fileSize;
        public String groupName;
        public String groupAvatarUrl;
        public List<String> memberIds;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = getQueryParam(session, "userId");
        if (userId != null && !userId.isBlank()) {
            userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
            session.getAttributes().put("userId", userId);
            log.info("Chat WS connected: userId={} sessionId={}", userId, session.getId());
            
            // Notify other active sessions of the user if needed
            sendSystemMessage(session, "AUTHENTICATED", "Successfully connected as " + userId);
        } else {
            log.warn("Chat WS connected without userId. session={}", session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            ChatPayload payload = objectMapper.readValue(message.getPayload(), ChatPayload.class);
            if (payload == null || payload.type == null) {
                sendError(session, "INVALID_PAYLOAD", "Missing message type.");
                return;
            }

            String sessionUserId = (String) session.getAttributes().get("userId");
            if (payload.type.equalsIgnoreCase("AUTH")) {
                handleAuth(session, payload);
                return;
            }

            if (sessionUserId == null) {
                sendError(session, "UNAUTHORIZED", "Please authenticate first.");
                return;
            }

            switch (payload.type.toUpperCase(Locale.ROOT)) {
                case "SEND_MSG" -> handleSendMessage(sessionUserId, payload);
                case "GET_HISTORY" -> handleGetHistory(session, sessionUserId, payload);
                case "CREATE_GROUP" -> handleCreateGroup(sessionUserId, payload);
                default -> sendError(session, "UNKNOWN_TYPE", "Message type " + payload.type + " not supported.");
            }
        } catch (Exception e) {
            log.error("Failed to parse chat message payload", e);
            sendError(session, "MALFORMED_JSON", "Invalid message payload.");
        }
    }

    private void handleAuth(WebSocketSession session, ChatPayload payload) throws IOException {
        if (payload.userId == null || payload.userId.isBlank()) {
            sendError(session, "INVALID_AUTH", "Missing userId.");
            return;
        }
        String oldUserId = (String) session.getAttributes().get("userId");
        if (oldUserId != null) {
            Set<WebSocketSession> oldSessions = userSessions.get(oldUserId);
            if (oldSessions != null) {
                oldSessions.remove(session);
            }
        }
        session.getAttributes().put("userId", payload.userId);
        userSessions.computeIfAbsent(payload.userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("Chat WS authenticated post-connection: userId={} session={}", payload.userId, session.getId());
        sendSystemMessage(session, "AUTHENTICATED", "Successfully authenticated as " + payload.userId);
    }

    private void handleSendMessage(String senderId, ChatPayload payload) throws IOException {
        ChatMessage chatMsg = new ChatMessage();
        chatMsg.setSenderId(senderId);
        chatMsg.setReceiverId(payload.receiverId);
        chatMsg.setGroupId(payload.groupId);
        chatMsg.setContent(payload.content);
        chatMsg.setMessageType(payload.messageType != null ? payload.messageType : "TEXT");
        chatMsg.setFileName(payload.fileName);
        chatMsg.setFileSize(payload.fileSize);
        
        chatMessageRepository.save(chatMsg);
        log.info("Chat message saved: sender={} msgId={}", senderId, chatMsg.getId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "MSG_RECEIVE");
        response.put("id", chatMsg.getId());
        response.put("senderId", senderId);
        response.put("receiverId", payload.receiverId);
        response.put("groupId", payload.groupId);
        response.put("content", payload.content);
        response.put("messageType", chatMsg.getMessageType());
        response.put("fileName", payload.fileName);
        response.put("fileSize", payload.fileSize);
        response.put("createdAt", System.currentTimeMillis());

        if (payload.groupId != null && !payload.groupId.isBlank()) {
            // Group Chat: broadcast to all active members of the group
            List<ChatGroupMember> members = chatGroupMemberRepository.findByGroupId(payload.groupId);
            for (ChatGroupMember member : members) {
                sendToUser(member.getUserId(), response);
            }
            // 群聊 @AI / @Echo：异步生成 AI 回复并广播给全群（设计书 §3.2.3）
            if ("TEXT".equalsIgnoreCase(chatMsg.getMessageType())
                    && aiAssistant.isAiMention(payload.content)) {
                dispatchGroupAiReply(payload.groupId, senderId, payload.content, members);
            }
        } else if (payload.receiverId != null && !payload.receiverId.isBlank()) {
            // Private Chat: relay to receiver and sender (to sync multi-device logs)
            sendToUser(payload.receiverId, response);
            sendToUser(senderId, response);
        }
    }

    /**
     * 群聊 @AI：在后台线程跑 LLM（避免阻塞 WS 线程），生成回复后以系统 AI 身份
     * 落库并广播给全群在线成员。失败时降级文案已在 {@link ChatAiAssistant} 内兜底。
     */
    private void dispatchGroupAiReply(String groupId, String askerId, String rawContent,
                                      List<ChatGroupMember> members) {
        String question = aiAssistant.stripMention(rawContent);
        aiExec.submit(() -> {
            try {
                String answer = aiAssistant.answerInGroup(groupId, askerId, question);

                ChatMessage aiMsg = new ChatMessage();
                aiMsg.setSenderId(ChatAiAssistant.AI_USER_ID);
                aiMsg.setGroupId(groupId);
                aiMsg.setContent(answer);
                aiMsg.setMessageType("TEXT");
                chatMessageRepository.save(aiMsg);

                Map<String, Object> aiResp = new LinkedHashMap<>();
                aiResp.put("type", "MSG_RECEIVE");
                aiResp.put("id", aiMsg.getId());
                aiResp.put("senderId", ChatAiAssistant.AI_USER_ID);
                aiResp.put("senderName", ChatAiAssistant.AI_DISPLAY_NAME);
                aiResp.put("groupId", groupId);
                aiResp.put("content", answer);
                aiResp.put("messageType", "TEXT");
                aiResp.put("isAi", true);
                aiResp.put("createdAt", System.currentTimeMillis());

                for (ChatGroupMember member : members) {
                    try {
                        sendToUser(member.getUserId(), aiResp);
                    } catch (Exception e) {
                        log.warn("Failed to deliver AI reply to {}: {}", member.getUserId(), e.toString());
                    }
                }
            } catch (Exception e) {
                log.error("Group @AI reply generation failed for group {}", groupId, e);
            }
        });
    }

    private void handleGetHistory(WebSocketSession session, String userId, ChatPayload payload) throws IOException {
        List<ChatMessage> history;
        if (payload.groupId != null && !payload.groupId.isBlank()) {
            history = chatMessageRepository.findByGroupIdOrderByCreatedAtAsc(payload.groupId);
        } else if (payload.receiverId != null && !payload.receiverId.isBlank()) {
            history = chatMessageRepository.findPrivateMessages(userId, payload.receiverId);
        } else {
            sendError(session, "INVALID_HISTORY_REQUEST", "Either groupId or receiverId is required.");
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "HISTORY_LIST");
        response.put("receiverId", payload.receiverId);
        response.put("groupId", payload.groupId);
        response.put("messages", history);
        sendMessage(session, response);
    }

    private void handleCreateGroup(String ownerId, ChatPayload payload) throws IOException {
        if (payload.groupName == null || payload.groupName.isBlank()) {
            return;
        }
        ChatGroup group = new ChatGroup();
        group.setName(payload.groupName);
        group.setAvatarUrl(payload.groupAvatarUrl);
        group.setOwnerId(ownerId);
        chatGroupRepository.save(group);

        // Add owner to members
        ChatGroupMember ownerMember = new ChatGroupMember();
        ownerMember.setGroupId(group.getId());
        ownerMember.setUserId(ownerId);
        chatGroupMemberRepository.save(ownerMember);

        // Add optional other members
        if (payload.memberIds != null) {
            for (String memberId : payload.memberIds) {
                if (memberId.equals(ownerId) || memberId.isBlank()) continue;
                ChatGroupMember member = new ChatGroupMember();
                member.setGroupId(group.getId());
                member.setUserId(memberId);
                chatGroupMemberRepository.save(member);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "GROUP_CREATED");
        response.put("groupId", group.getId());
        response.put("name", group.getName());
        response.put("avatarUrl", group.getAvatarUrl());

        // Notify all member IDs
        sendToUser(ownerId, response);
        if (payload.memberIds != null) {
            for (String memberId : payload.memberIds) {
                sendToUser(memberId, response);
            }
        }
    }

    private void sendToUser(String userId, Map<String, Object> msg) throws IOException {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json = objectMapper.writeValueAsString(msg);
        TextMessage textMsg = new TextMessage(json);
        List<WebSocketSession> deadSessions = new ArrayList<>();
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                s.sendMessage(textMsg);
            } else {
                deadSessions.add(s);
            }
        }
        for (WebSocketSession dead : deadSessions) {
            sessions.remove(dead);
        }
        if (sessions.isEmpty()) {
            userSessions.remove(userId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
        log.info("Chat WS disconnected: session={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Chat WS transport error on session={}", session.getId(), exception);
        removeSession(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void removeSession(WebSocketSession session) {
        String userId = (String) session.getAttributes().get("userId");
        if (userId != null) {
            Set<WebSocketSession> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    userSessions.remove(userId);
                }
            }
        }
    }

    private void sendSystemMessage(WebSocketSession session, String code, String text) throws IOException {
        Map<String, Object> sysMsg = Map.of(
                "type", "SYSTEM",
                "code", code,
                "message", text
        );
        sendMessage(session, sysMsg);
    }

    private void sendError(WebSocketSession session, String code, String text) throws IOException {
        Map<String, Object> errMsg = Map.of(
                "type", "ERROR",
                "code", code,
                "message", text
        );
        sendMessage(session, errMsg);
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> msg) throws IOException {
        if (session.isOpen()) {
            String json = objectMapper.writeValueAsString(msg);
            session.sendMessage(new TextMessage(json));
        }
    }

    private String getQueryParam(WebSocketSession session, String key) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1 && pair[0].equals(key)) {
                    return pair[1];
                }
            }
        }
        return null;
    }
}
