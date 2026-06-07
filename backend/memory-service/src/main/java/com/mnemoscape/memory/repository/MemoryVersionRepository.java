package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.model.entity.MemoryVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface MemoryVersionRepository extends JpaRepository<MemoryVersion, String> {
    List<MemoryVersion> findByMemoryIdOrderByVersionNumberDesc(String memoryId);
    int countByMemoryId(String memoryId);

    /**
     * 管理后台批量删除时配套使用：把指定 memory 的所有版本快照一次性清掉，
     * 避免外键残留导致后续 DELETE FROM memories 触发约束冲突。
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM MemoryVersion v WHERE v.memoryId = :memoryId")
    int deleteByMemoryId(String memoryId);
}
