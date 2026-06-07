package com.mnemoscape.resonance.repository;

import com.mnemoscape.resonance.model.entity.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface SupportTicketRepository
        extends JpaRepository<SupportTicket, String>, JpaSpecificationExecutor<SupportTicket> {
    Page<SupportTicket> findByUserIdOrderByLastMessageAtDesc(String userId, Pageable pageable);
    List<SupportTicket> findByStatus(String status);
    long countByStatus(String status);
}
