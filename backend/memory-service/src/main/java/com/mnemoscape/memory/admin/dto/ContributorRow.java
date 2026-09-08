package com.mnemoscape.memory.admin.dto;

/**
 * JPA projection interface for the top-contributors query in MemoryRepository.
 */
public interface ContributorRow {
    String getUserId();
    Long getMemoryCount();
}
