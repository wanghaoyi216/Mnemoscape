package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.model.entity.DriftBottle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DriftBottleRepository extends JpaRepository<DriftBottle, Long> {

    List<DriftBottle> findByUserIdOrderByThrownAtDesc(String userId);

    List<DriftBottle> findByPickedByUserIdOrderByPickedAtDesc(String userId);

    @Query("SELECT b FROM DriftBottle b WHERE b.isActive = true AND b.pickedByUserId IS NULL ORDER BY RAND()")
    List<DriftBottle> findRandomActiveBottles(org.springframework.data.domain.Pageable pageable);

    Optional<DriftBottle> findByIdAndIsActiveTrue(Long id);
}
