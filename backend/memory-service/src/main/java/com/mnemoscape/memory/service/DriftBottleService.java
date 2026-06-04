package com.mnemoscape.memory.service;

import com.mnemoscape.memory.model.entity.DriftBottle;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.repository.DriftBottleRepository;
import com.mnemoscape.memory.repository.MemoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DriftBottleService {

    private final DriftBottleRepository bottleRepo;
    private final MemoryRepository memoryRepo;

    public DriftBottleService(DriftBottleRepository bottleRepo, MemoryRepository memoryRepo) {
        this.bottleRepo = bottleRepo;
        this.memoryRepo = memoryRepo;
    }

    /**
     * 投掷漂流瓶。
     */
    @Transactional
    public DriftBottle throwBottle(String userId, String memoryId, String snippet) {
        Optional<Memory> memOpt = memoryRepo.findById(memoryId);
        if (memOpt.isEmpty() || !memOpt.get().getUserId().equals(userId)) {
            throw new IllegalArgumentException("Memory not found or access denied");
        }

        Memory memory = memOpt.get();

        DriftBottle bottle = new DriftBottle(userId, memoryId, snippet);
        bottle.setEmotion(extractDominantEmotion(memory));
        bottle.setLocation(memory.getMemoryLocation());
        bottle.setYear(memory.getMemoryYear());

        return bottleRepo.save(bottle);
    }

    /**
     * 随机捡起一个漂流瓶。
     */
    @Transactional
    public Optional<DriftBottle> pickRandomBottle(String userId) {
        List<DriftBottle> candidates = bottleRepo.findRandomActiveBottles(PageRequest.of(0, 10));

        // 过滤掉自己投掷的瓶子
        Optional<DriftBottle> bottle = candidates.stream()
                .filter(b -> !b.getUserId().equals(userId))
                .findFirst();

        if (bottle.isPresent()) {
            DriftBottle b = bottle.get();
            b.setPickedByUserId(userId);
            b.setPickedAt(LocalDateTime.now());
            b.setIsActive(false);
            bottleRepo.save(b);
        }

        return bottle;
    }

    /**
     * 获取用户投掷的瓶子列表。
     */
    public List<DriftBottle> getMyBottles(String userId) {
        return bottleRepo.findByUserIdOrderByThrownAtDesc(userId);
    }

    /**
     * 获取用户捡到的瓶子列表。
     */
    public List<DriftBottle> getPickedBottles(String userId) {
        return bottleRepo.findByPickedByUserIdOrderByPickedAtDesc(userId);
    }

    private String extractDominantEmotion(Memory memory) {
        if (memory.getEmotionProfile() == null) return "calm";
        // 简化版：直接返回 calm，实际应解析 JSON
        return "calm";
    }
}
