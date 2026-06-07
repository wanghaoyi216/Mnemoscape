package com.mnemoscape.resonance.repository;

import com.mnemoscape.resonance.model.entity.ChatGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatGroupRepository extends JpaRepository<ChatGroup, String> {
}
