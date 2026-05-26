package com.mnemoscape.resonance.repository;

import com.mnemoscape.resonance.model.entity.ResonanceSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ResonanceSpaceRepository extends JpaRepository<ResonanceSpace, String> {
    @Query("SELECT r FROM ResonanceSpace r WHERE r.memoryId1 = :memoryId OR r.memoryId2 = :memoryId")
    List<ResonanceSpace> findByMemoryId(String memoryId);
}
