package com.mnemoscape.resonance.controller;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.web.RequestContext;
import com.mnemoscape.resonance.model.entity.ChatGroup;
import com.mnemoscape.resonance.model.entity.ChatGroupMember;
import com.mnemoscape.resonance.model.entity.ChatMessage;
import com.mnemoscape.resonance.repository.ChatGroupMemberRepository;
import com.mnemoscape.resonance.repository.ChatGroupRepository;
import com.mnemoscape.resonance.repository.ChatMessageRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatMessageRepository chatMessageRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final ChatGroupMemberRepository chatGroupMemberRepository;

    public ChatController(ChatMessageRepository chatMessageRepository,
                          ChatGroupRepository chatGroupRepository,
                          ChatGroupMemberRepository chatGroupMemberRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.chatGroupMemberRepository = chatGroupMemberRepository;
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
}
