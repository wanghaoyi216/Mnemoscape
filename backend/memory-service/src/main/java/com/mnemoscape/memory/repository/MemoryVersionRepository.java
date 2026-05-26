package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.model.entity.MemoryVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MemoryVersionRepository extends JpaRepository<MemoryVersion, String> {
    List<MemoryVersion> findByMemoryIdOrderByVersionNumberDesc(String memoryId);
    int countByMemoryId(String memoryId);
}
