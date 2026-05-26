package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.model.entity.Memory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MemoryRepository extends JpaRepository<Memory, String> {
    Page<Memory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
    List<Memory> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Memory> findByIsLockedFalse();

    @Query("SELECT m FROM Memory m WHERE m.userId = :userId AND m.privacyLevel = :privacyLevel")
    List<Memory> findByUserIdAndPrivacyLevel(String userId, Memory.PrivacyLevel privacyLevel);

    default List<Memory> findPublicByUserId(String userId) {
        return findByUserIdAndPrivacyLevel(userId, Memory.PrivacyLevel.PUBLIC);
    }

    Page<Memory> findByUserIdAndPrivacyLevelOrderByCreatedAtDesc(String userId, Memory.PrivacyLevel privacyLevel, Pageable pageable);
}
