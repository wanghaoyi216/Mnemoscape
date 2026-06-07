package com.mnemoscape.resonance.repository;

import com.mnemoscape.resonance.model.entity.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, String> {
    List<SupportMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);

    @Modifying
    @Transactional
    @Query("DELETE FROM SupportMessage m WHERE m.ticketId = :ticketId")
    int deleteByTicketId(String ticketId);

    /** 工单内未读消息计数（用户视角：senderRole=ADMIN 且 readAt IS NULL）。 */
    @Query("SELECT COUNT(m) FROM SupportMessage m WHERE m.ticketId = :ticketId "
         + "AND m.senderRole = :senderRole AND m.readAt IS NULL")
    long countUnread(String ticketId, String senderRole);
}
