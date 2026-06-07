package com.mnemoscape.resonance.repository;

import com.mnemoscape.resonance.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {
    
    @Query("SELECT m FROM ChatMessage m WHERE " +
           "(m.senderId = :user1 AND m.receiverId = :user2) OR " +
           "(m.senderId = :user2 AND m.receiverId = :user1) " +
           "ORDER BY m.createdAt ASC")
    List<ChatMessage> findPrivateMessages(
            @Param("user1") String user1, 
            @Param("user2") String user2);

    List<ChatMessage> findByGroupIdOrderByCreatedAtAsc(String groupId);
}
