package com.mnemoscape.memory.service;

import com.mnemoscape.common.event.AchievementUnlockedEvent;
import com.mnemoscape.common.lock.DistributedLockStore;
import com.mnemoscape.memory.messaging.EventPublisher;
import com.mnemoscape.memory.model.entity.Achievement;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.repository.AchievementRepository;
import com.mnemoscape.memory.repository.MemoryRepository;
import com.mnemoscape.memory.repository.MemoryFragmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AchievementService {

    private static final Logger log = LoggerFactory.getLogger(AchievementService.class);

    private final AchievementRepository achievementRepo;
    private final MemoryRepository memoryRepo;
    private final MemoryFragmentRepository fragmentRepo;
    private final EventPublisher eventPublisher;
    private final DistributedLockStore lock;

    public AchievementService(AchievementRepository achievementRepo,
                              MemoryRepository memoryRepo,
                              MemoryFragmentRepository fragmentRepo,
                              EventPublisher eventPublisher,
                              DistributedLockStore lock) {
        this.achievementRepo = achievementRepo;
        this.memoryRepo = memoryRepo;
        this.fragmentRepo = fragmentRepo;
        this.eventPublisher = eventPublisher;
        this.lock = lock;
    }

    public List<Achievement> getUserAchievements(String userId) {
        return achievementRepo.findByUserIdOrderByUnlockedAtDesc(userId);
    }

    /**
     * 检查并解锁成就。加分布式锁防止同一用户并发触发重复解锁
     * (如两条记忆同时创建都触发 checkAndUnlock)。
     * 拿不到锁时返回空列表,下次记忆创建会再次触发,最终一致。
     */
    public List<Achievement> checkAndUnlock(String userId) {
        List<Achievement> result = lock.execute("achievement:" + userId, 0, 30, () -> doCheckAndUnlock(userId));
        return result != null ? result : List.of();
    }

    private List<Achievement> doCheckAndUnlock(String userId) {
        List<Achievement> newlyUnlocked = new ArrayList<>();

        List<Memory> memories = memoryRepo.findByUserIdOrderByCreatedAtDesc(userId);
        long fragmentCount = fragmentRepo.countDiscoveredByUserId(userId);

        // First Memory
        if (!memories.isEmpty()) {
            newlyUnlocked.addAll(tryUnlock(userId, "first_memory",
                    "初心者", "创建了第一条记忆", "star"));
        }

        // Time Traveler — memories spanning 5+ years
        if (memories.size() >= 2) {
            OptionalInt minYear = memories.stream()
                    .filter(m -> m.getMemoryYear() != null)
                    .mapToInt(Memory::getMemoryYear).min();
            OptionalInt maxYear = memories.stream()
                    .filter(m -> m.getMemoryYear() != null)
                    .mapToInt(Memory::getMemoryYear).max();
            if (minYear.isPresent() && maxYear.isPresent() && maxYear.getAsInt() - minYear.getAsInt() >= 5) {
                newlyUnlocked.addAll(tryUnlock(userId, "time_traveler",
                        "时光旅人", "记忆跨越 5 年以上", "clock"));
            }
        }

        // Globe Trotter — 3+ distinct locations
        long distinctLocations = memories.stream()
                .map(Memory::getMemoryLocation)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct().count();
        if (distinctLocations >= 3) {
            newlyUnlocked.addAll(tryUnlock(userId, "globe_trotter",
                    "环球记忆", "记忆覆盖 3 个以上不同地点", "globe"));
        }

        // Memory Collector — 10+ memories
        if (memories.size() >= 10) {
            newlyUnlocked.addAll(tryUnlock(userId, "memory_collector",
                    "记忆收藏家", "累计创建 10 条记忆", "archive"));
        }

        // Fragment Hunter — 50+ fragments discovered
        if (fragmentCount >= 50) {
            newlyUnlocked.addAll(tryUnlock(userId, "fragment_hunter",
                    "碎片猎人", "收集 50 个记忆碎片", "puzzle"));
        }

        // Season Master — memories in all 4 seasons
        Set<String> seasons = new HashSet<>();
        for (Memory m : memories) {
            if (m.getMemorySeason() != null) seasons.add(m.getMemorySeason());
        }
        if (seasons.size() >= 4) {
            newlyUnlocked.addAll(tryUnlock(userId, "season_master",
                    "四季行者", "记忆覆盖春夏秋冬四个季节", "seasons"));
        }

        // Prolific Writer — 20+ memories
        if (memories.size() >= 20) {
            newlyUnlocked.addAll(tryUnlock(userId, "prolific_writer",
                    "星空诗人", "累计创建 20 条记忆", "pen"));
        }

        return newlyUnlocked;
    }

    private List<Achievement> tryUnlock(String userId, String key, String title, String desc, String icon) {
        if (achievementRepo.existsByUserIdAndAchievementKey(userId, key)) {
            return List.of();
        }
        Achievement a = new Achievement(userId, key, title, desc, icon);
        achievementRepo.save(a);
        log.info("Achievement unlocked: userId={}, key={}", userId, key);
        // MQ 扇出：resonance-service 写系统通知 + 失效 top-contributors 缓存
        eventPublisher.publishAchievementUnlocked(AchievementUnlockedEvent.of(
                userId, key, title, null));
        return List.of(a);
    }
}
