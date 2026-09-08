package com.mnemoscape.memory.admin;

import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.memory.admin.AdminMemoryManagementController.AdminMemoryRow;
import com.mnemoscape.memory.model.entity.Memory;
import com.mnemoscape.memory.repository.MemoryFragmentRepository;
import com.mnemoscape.memory.repository.MemoryRepository;
import com.mnemoscape.memory.repository.MemoryVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台记忆管理的业务层。事务边界贴在此处,Controller 只做 HTTP 协议层。
 */
@Slf4j
@Service
public class AdminMemoryManagementService {

    private final MemoryRepository memoryRepository;
    private final MemoryFragmentRepository fragmentRepository;
    private final MemoryVersionRepository versionRepository;

    public AdminMemoryManagementService(MemoryRepository memoryRepository,
                                        MemoryFragmentRepository fragmentRepository,
                                        MemoryVersionRepository versionRepository) {
        this.memoryRepository = memoryRepository;
        this.fragmentRepository = fragmentRepository;
        this.versionRepository = versionRepository;
    }

    /** 批量删除结果。 */
    public record BatchDeleteResult(int deleted, List<String> failed) {}

    /** 批量更新结果。 */
    public record BatchUpdateResult(int updated, String privacyLevel, Boolean locked) {}

    /** 分页查询记忆。spec 构建挪到 Service,HTTP 格式校验留 Controller。 */
    public Page<Memory> list(int safePage, int safeSize, String sortBy, String sortDir,
                             String search, String userId, String privacyLevel, Boolean locked) {
        Sort sort = "asc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        PageRequest pageReq = PageRequest.of(safePage, safeSize, sort);

        Specification<Memory> spec = (root, query, cb) -> cb.conjunction();
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("title")), pattern));
        }
        if (userId != null && !userId.isBlank()) {
            String uid = userId.trim();
            spec = spec.and((root, q, cb) -> cb.equal(root.get("userId"), uid));
        }
        if (privacyLevel != null && !privacyLevel.isBlank()) {
            // 业务校验:privacyLevel 枚举合法性
            Memory.PrivacyLevel pl = Memory.PrivacyLevel.valueOf(privacyLevel.trim().toUpperCase());
            spec = spec.and((root, q, cb) -> cb.equal(root.get("privacyLevel"), pl));
        }
        if (locked != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("isLocked"), locked));
        }
        return memoryRepository.findAll(spec, pageReq);
    }

    /** 批量删除:清空 fragments / versions 后删除记忆。整个循环在同一事务。 */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "publicPool", allEntries = true),
            @CacheEvict(value = "memories", allEntries = true)
    })
    public BatchDeleteResult batchDelete(List<String> ids) {
        int deleted = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            try {
                fragmentRepository.deleteByMemoryId(id);
                versionRepository.deleteByMemoryId(id);
                memoryRepository.deleteById(id);
                deleted++;
            } catch (Exception e) {
                log.warn("[admin] batch-delete failed for memory {}: {}", id, e.toString());
                failed.add(id);
            }
        }
        return new BatchDeleteResult(deleted, failed);
    }

    /** 批量更新隐私级别。业务校验(枚举合法)挪到此层。 */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "publicPool", allEntries = true),
            @CacheEvict(value = "memories", allEntries = true)
    })
    public BatchUpdateResult batchUpdatePrivacy(List<String> ids, String privacy) {
        // 业务校验:privacyLevel 枚举合法性
        Memory.PrivacyLevel target = Memory.PrivacyLevel.valueOf(privacy.trim().toUpperCase());
        int updated = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            var opt = memoryRepository.findById(id);
            if (opt.isPresent()) {
                Memory m = opt.get();
                m.setPrivacyLevel(target);
                m.setUpdatedAt(LocalDateTime.now());
                memoryRepository.save(m);
                updated++;
            }
        }
        return new BatchUpdateResult(updated, target.name(), null);
    }

    /** 批量锁定 / 解锁。 */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "publicPool", allEntries = true),
            @CacheEvict(value = "memories", allEntries = true)
    })
    public BatchUpdateResult batchLock(List<String> ids, Boolean locked) {
        int updated = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            var opt = memoryRepository.findById(id);
            if (opt.isPresent()) {
                Memory m = opt.get();
                m.setIsLocked(locked);
                m.setUpdatedAt(LocalDateTime.now());
                memoryRepository.save(m);
                updated++;
            }
        }
        return new BatchUpdateResult(updated, null, locked);
    }

    /** 删除单条:业务校验(存在性 404)挪到此层。 */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "memories", key = "#id"),
            @CacheEvict(value = "publicPool", allEntries = true)
    })
    public void deleteOne(String id) {
        if (!memoryRepository.existsById(id)) {
            throw new BizException(404, "MEMORY_NOT_FOUND");
        }
        try {
            fragmentRepository.deleteByMemoryId(id);
            versionRepository.deleteByMemoryId(id);
            memoryRepository.deleteById(id);
        } catch (Exception e) {
            log.error("[admin] delete failed for memory {}", id, e);
            throw new BizException(500, "DELETE_FAILED");
        }
    }

    /** 行内编辑:单条更新隐私级别 / 锁定 / fadeLevel。body 字段全 optional。 */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "memories", key = "#id"),
            @CacheEvict(value = "publicPool", allEntries = true)
    })
    public Memory patchOne(String id, Map<String, Object> body) {
        var opt = memoryRepository.findById(id);
        if (opt.isEmpty()) {
            throw new BizException(404, "MEMORY_NOT_FOUND");
        }
        Memory m = opt.get();
        if (body.containsKey("privacyLevel")) {
            String p = (String) body.get("privacyLevel");
            if (p != null && !p.isBlank()) {
                // 业务校验:枚举合法性
                m.setPrivacyLevel(Memory.PrivacyLevel.valueOf(p.trim().toUpperCase()));
            }
        }
        if (body.containsKey("locked")) {
            Object v = body.get("locked");
            if (v instanceof Boolean b) m.setIsLocked(b);
        }
        if (body.containsKey("fadeLevel")) {
            Object v = body.get("fadeLevel");
            if (v instanceof Number n) m.setFadeLevel(n.doubleValue());
        }
        m.setUpdatedAt(LocalDateTime.now());
        return memoryRepository.save(m);
    }

    /** 批量查询记忆详情。 */
    public Map<String, Memory> getBatchDetails(List<String> ids) {
        List<Memory> memories = memoryRepository.findAllById(ids);
        Map<String, Memory> result = new HashMap<>();
        for (Memory m : memories) {
            result.put(m.getId(), m);
        }
        return result;
    }
}
