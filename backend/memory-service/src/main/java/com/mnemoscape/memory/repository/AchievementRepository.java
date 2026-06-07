package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.model.entity.Achievement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AchievementRepository extends JpaRepository<Achievement, Long> {

    List<Achievement> findByUserIdOrderByUnlockedAtDesc(String userId);

    Optional<Achievement> findByUserIdAndAchievementKey(String userId, String achievementKey);

    boolean existsByUserIdAndAchievementKey(String userId, String achievementKey);
}
