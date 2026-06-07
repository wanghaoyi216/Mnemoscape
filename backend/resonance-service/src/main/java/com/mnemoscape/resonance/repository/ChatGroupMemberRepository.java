package com.mnemoscape.resonance.repository;

import com.mnemoscape.resonance.model.entity.ChatGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ChatGroupMemberRepository extends JpaRepository<ChatGroupMember, String> {
    List<ChatGroupMember> findByUserId(String userId);
    List<ChatGroupMember> findByGroupId(String groupId);
    Optional<ChatGroupMember> findByGroupIdAndUserId(String groupId, String userId);
}
