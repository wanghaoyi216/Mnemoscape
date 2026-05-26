package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.model.entity.MemoryFragment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MemoryFragmentRepository extends JpaRepository<MemoryFragment, String> {
    List<MemoryFragment> findByMemoryId(String memoryId);
    List<MemoryFragment> findByMemoryIdAndIsDiscovered(String memoryId, Boolean isDiscovered);
}
