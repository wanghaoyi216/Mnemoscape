package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天记忆仓储 (ChatMemoryRepository)。
 */
@Repository
public interface ChatMemoryRepository extends JpaRepository<ChatMessage, String> {

    /**
     * 按 userId 与 sessionId 查询未归档的聊天记忆（按创建时间升序）。
     */
    List<ChatMessage> findByUserIdAndSessionIdAndArchivedFalseOrderByCreatedAtAsc(String userId, String sessionId);

    /**
     * 按 userId 与 sessionId 查询所有聊天记忆（按创建时间升序）。
     */
    List<ChatMessage> findByUserIdAndSessionIdOrderByCreatedAtAsc(String userId, String sessionId);

    /**
     * 按 userId 查询所有未归档的聊天记忆。
     */
    List<ChatMessage> findByUserIdAndArchivedFalseOrderByCreatedAtAsc(String userId);

    /**
     * 按 userId 查询在指定截止时间之后的聊天记录。
     */
    List<ChatMessage> findByUserIdAndCreatedAtAfter(String userId, LocalDateTime cutoff);

    /**
     * 查询在指定截止时间之前且尚未归档的消息（供冷热归档定时任务扫描）。
     */
    List<ChatMessage> findByArchivedFalseAndCreatedAtBefore(LocalDateTime cutoff);

    /**
     * 批量标记消息为已归档并设置归档对象 key。
     */
    @Modifying
    @Query("UPDATE ChatMessage m SET m.archived = true, m.archiveKey = :archiveKey WHERE m.id IN :ids")
    void markArchived(@Param("ids") List<String> ids, @Param("archiveKey") String archiveKey);
}
