package com.mnemoscape.memory.service;

import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.repository.MemoryRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Cached identity lookup for memories.
 *
 * <p>Lives in its own Spring bean so that {@code MemoryService.getMemory(...)}
 * crosses a proxy boundary when it calls {@link #findById(String)} — without
 * that hop the {@code @Cacheable} annotation would be silently bypassed when
 * invoked from a method on the same class.</p>
 */
@Component
public class MemoryLookup {

    private final MemoryRepository memoryRepository;

    public MemoryLookup(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Cacheable(value = "memories", key = "#memoryId", sync = true)
    public Memory findById(String memoryId) {
        return memoryRepository.findById(memoryId)
                .orElseThrow(() -> BizException.notFound("Memory", memoryId));
    }

    @CacheEvict(value = "memories", key = "#memoryId")
    public void evict(String memoryId) {
        // no-op body; the annotation does the work
    }

    @CacheEvict(value = "memories", allEntries = true)
    public void evictAll() {
        // no-op body; the annotation does the work
    }
}
