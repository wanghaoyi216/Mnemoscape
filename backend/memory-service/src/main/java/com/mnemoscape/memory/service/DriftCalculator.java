package com.mnemoscape.memory.service;

import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DriftCalculator {
    private static final Logger log = LoggerFactory.getLogger(DriftCalculator.class);
    private static final double DECAY_CONSTANT = 100.0; // days

    private final MemoryRepository memoryRepository;

    public DriftCalculator(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    public Map<String, Object> getDriftState(Memory memory) {
        calculateAndApply(memory);
        double fadeLevel = memory.getFadeLevel() != null ? memory.getFadeLevel() : 0.0;
        long daysSinceCreation = ChronoUnit.DAYS.between(memory.getCreatedAt().toLocalDate(),
                java.time.LocalDate.now());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("memoryId", memory.getId());
        state.put("fadeLevel", Math.min(1.0, Math.max(0.0, fadeLevel)));
        state.put("daysSinceCreation", daysSinceCreation);
        state.put("colorSaturation", 1.0 - fadeLevel * 0.7);
        state.put("fogDensity", fadeLevel * 0.5);
        state.put("audioReverb", fadeLevel * 0.5);
        state.put("isLocked", memory.getIsLocked());
        state.put("calculatedAt", LocalDateTime.now().toString());
        return state;
    }

    public void calculateAndApply(Memory memory) {
        if (Boolean.TRUE.equals(memory.getIsLocked())) {
            return;
        }

        if (memory.getCreatedAt() == null) {
            memory.setFadeLevel(0.0);
            return;
        }

        long daysSinceCreation = ChronoUnit.DAYS.between(
                memory.getCreatedAt().toLocalDate(), java.time.LocalDate.now());
        double retention = Math.exp(-daysSinceCreation / DECAY_CONSTANT);
        double fadeLevel = 1.0 - retention;

        memory.setFadeLevel(Math.min(1.0, Math.max(0.0, fadeLevel)));
        memory.setLastDriftCalculatedAt(LocalDateTime.now());
    }

    @Scheduled(fixedRate = 3600000) // Every hour
    public void scheduledDriftUpdate() {
        log.info("Running scheduled drift update");
        List<Memory> unlockedMemories = memoryRepository.findByIsLockedFalse();
        for (Memory memory : unlockedMemories) {
            try {
                calculateAndApply(memory);
                memoryRepository.save(memory);
            } catch (Exception e) {
                log.error("Failed to update drift for memory {}", memory.getId(), e);
            }
        }
        log.info("Drift update completed for {} memories", unlockedMemories.size());
    }
}
