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

    @Modifying
    @Transactional
    @Query("DELETE FROM MemoryFragment f WHERE f.memoryId = :memoryId")
    int deleteByMemoryId(String memoryId);
}
