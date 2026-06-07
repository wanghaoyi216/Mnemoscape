package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.model.entity.MemoryFragment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MemoryFragmentRepository extends JpaRepository<MemoryFragment, String> {
    List<MemoryFragment> findByMemoryId(String memoryId);
    List<MemoryFragment> findByMemoryIdAndIsDiscovered(String memoryId, Boolean isDiscovered);

    @Query("SELECT COUNT(f) FROM MemoryFragment f WHERE f.memoryId IN (SELECT m.id FROM com.mnemoscape.memory.model.entity.Memory m WHERE m.userId = :userId) AND f.isDiscovered = true")
    long countDiscoveredByUserId(@org.springframework.data.repository.query.Param("userId") String userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM MemoryFragment f WHERE f.memoryId = :memoryId")
    int deleteByMemoryId(String memoryId);
}
