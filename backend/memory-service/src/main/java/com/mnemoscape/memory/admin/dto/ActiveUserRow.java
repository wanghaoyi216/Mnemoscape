package com.mnemoscape.memory.admin.dto;

import java.time.LocalDateTime;

/**
 * JPA projection interface for the active-user feed query in MemoryRepository.
 */
public interface ActiveUserRow {
    String getUserId();
    LocalDateTime getLatestActivityAt();
}
