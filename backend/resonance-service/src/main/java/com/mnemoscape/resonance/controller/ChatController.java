package com.mnemoscape.resonance.controller;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import com.mnemoscape.resonance.model.entity.ChatGroup;
import com.mnemoscape.resonance.model.entity.ChatGroupMember;
import com.mnemoscape.resonance.model.entity.ChatMessage;
import com.mnemoscape.resonance.repository.ChatGroupMemberRepository;
import com.mnemoscape.resonance.repository.ChatGroupRepository;
import com.mnemoscape.resonance.repository.ChatMessageRepository;
import com.mnemoscape.resonance.service.ChatAiAssistant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatMessageRepository chatMessageRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final ChatGroupMemberRepository chatGroupMemberRepository;
    private final ChatAiAssistant aiAssistant;

    public ChatController(ChatMessageRepository chatMessageRepository,
                          ChatGroupRepository chatGroupRepository,
                          ChatGroupMemberRepository chatGroupMemberRepository,
                          ChatAiAssistant aiAssistant) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.chatGroupMemberRepository = chatGroupMemberRepository;
        this.aiAssistant = aiAssistant;
    }

    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<List<ChatMessage>>> getHistory(
            @RequestParam(required = false) String receiverId,
            @RequestParam(required = false) String groupId,
            HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        List<ChatMessage> history;
        if (groupId != null && !groupId.isBlank()) {
            history = chatMessageRepository.findByGroupIdOrderByCreatedAtAsc(groupId);
        } else if (receiverId != null && !receiverId.isBlank()) {
            history = chatMessageRepository.findPrivateMessages(userId, receiverId);
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("Missing receiverId or groupId"));
        }
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/groups")
    public ResponseEntity<ApiResponse<List<ChatGroup>>> getMyGroups(HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        List<ChatGroupMember> memberships = chatGroupMemberRepository.findByUserId(userId);
        List<ChatGroup> groups = new ArrayList<>();
        for (ChatGroupMember m : memberships) {
            chatGroupRepository.findById(m.getGroupId()).ifPresent(groups::add);
        }
        return ResponseEntity.ok(ApiResponse.success(groups));
    }

    @PostMapping("/groups")
    public ResponseEntity<ApiResponse<ChatGroup>> createGroup(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String ownerId = RequestContext.requireUserId(request);
        String name = (String) body.get("name");
        String avatarUrl = (String) body.get("avatarUrl");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("Group name is required"));
        }

        ChatGroup group = new ChatGroup();
        group.setName(name.trim());
        group.setAvatarUrl(avatarUrl);
        group.setOwnerId(ownerId);
        chatGroupRepository.save(group);

        // Add owner to members
        ChatGroupMember ownerMember = new ChatGroupMember();
        ownerMember.setGroupId(group.getId());
        ownerMember.setUserId(ownerId);
        chatGroupMemberRepository.save(ownerMember);

        // Add other member IDs if present
        List<?> memberIds = (List<?>) body.get("memberIds");
        if (memberIds != null) {
            for (Object mIdObj : memberIds) {
                String mId = String.valueOf(mIdObj).trim();
                if (mId.equals(ownerId) || mId.isEmpty()) continue;
                ChatGroupMember member = new ChatGroupMember();
                member.setGroupId(group.getId());
                member.setUserId(mId);
                chatGroupMemberRepository.save(member);
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(group));
    }

    /**
     * 私聊"求助星空使者破冰"（设计书 §3.2.3）。
     *
     * <p>AI 分析当前用户与 {@code otherId} 的最近对话，给出一条破冰 / 话题引导建议。
     * 该建议<b>不落库、不广播</b> —— 它只返回给发起者，由前端决定要不要采用 / 发送。
     * 这样设计避免 AI 替用户"擅自发言"，把主动权留给用户。
     */
    @PostMapping("/icebreaker")
    public ResponseEntity<ApiResponse<Map<String, Object>>> icebreaker(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        String otherId = body.get("otherId") == null ? null : String.valueOf(body.get("otherId"));
        if (otherId == null || otherId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("Missing otherId"));
        }
        String suggestion = aiAssistant.icebreakerForPrivate(userId, otherId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("suggestion", suggestion);
        data.put("aiName", ChatAiAssistant.AI_DISPLAY_NAME);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
