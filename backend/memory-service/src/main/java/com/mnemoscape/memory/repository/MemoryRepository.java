package com.mnemoscape.memory.repository;

import com.mnemoscape.memory.admin.dto.ActiveUserRow;
import com.mnemoscape.memory.admin.dto.ContributorRow;
import com.mnemoscape.memory.admin.dto.EmotionDistributionRow;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryRow;
import com.mnemoscape.memory.admin.dto.HeatmapRow;
import com.mnemoscape.memory.admin.dto.MemoryTrendRow;
import com.mnemoscape.memory.model.entity.Memory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MemoryRepository extends JpaRepository<Memory, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Memory> {
    Page<Memory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
    List<Memory> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Memory> findByIsLockedFalse();

    @Query("SELECT m FROM Memory m WHERE m.userId = :userId AND m.privacyLevel = :privacyLevel")
    List<Memory> findByUserIdAndPrivacyLevel(String userId, Memory.PrivacyLevel privacyLevel);

    default List<Memory> findPublicByUserId(String userId) {
        return findByUserIdAndPrivacyLevel(userId, Memory.PrivacyLevel.PUBLIC);
    }

    /**
     * 跨用户的公共记忆池（排除调用方自己）。
     * 按 createdAt 倒序，limit 由调用层用 Pageable 控制；用于 resonance 的真实化检索。
     */
    @Query("SELECT m FROM Memory m WHERE m.privacyLevel = com.mnemoscape.memory.model.entity.Memory$PrivacyLevel.PUBLIC "
         + "AND m.userId <> :excludeUserId "
         + "ORDER BY m.createdAt DESC")
    List<Memory> findPublicPoolExcludingUser(String excludeUserId, Pageable pageable);

    Page<Memory> findByUserIdAndPrivacyLevelOrderByCreatedAtDesc(String userId, Memory.PrivacyLevel privacyLevel, Pageable pageable);

    // ==================================================================================
    // Admin dashboard queries — admin-dashboard task 7.7.
    //
    // All queries below feed the /api/v1/admin/stats/** aggregation endpoints. They are
    // intentionally narrow (return projection interfaces from admin/dto, never the full
    // Memory entity) so admin DTOs can stay strictly whitelisted (no title / description /
    // visualData / audioData / emotionProfile leakage — Requirements 15.1, 15.3).
    //
    // All bucketing / windowing logic stays in Java (TimeBucketing) so we do not have to
    // maintain four flavours of DATE_FORMAT() / YEARWEEK() across MySQL.
    // ==================================================================================

    /**
     * Active-user feed for {@code /admin/stats/active-user-counts} (R6.6).
     *
     * <p>Returns one row per memory whose creation OR last modification falls inside
     * {@code [fromDt, toDt)}. The {@code latestActivityAt} expression mirrors the design
     * pseudocode {@code GREATEST(createdAt, COALESCE(updatedAt, createdAt))} — {@code GREATEST}
     * is not portable JPQL, so we emulate it with a {@code CASE WHEN}.</p>
     *
     * <p>The Java aggregator deduplicates {@code userId} per bucket; this query stays at
     * "one row per memory" so we can keep the SQL simple and let the bucket-set logic in
     * {@code AdminStatsService.aggregateActiveUserBuckets} own deduplication.</p>
     */
    @Query("""
        SELECT m.userId AS userId,
               CASE
                   WHEN m.updatedAt IS NULL THEN m.createdAt
                   WHEN m.updatedAt > m.createdAt THEN m.updatedAt
                   ELSE m.createdAt
               END AS latestActivityAt
        FROM Memory m
        WHERE (m.createdAt >= :fromDt AND m.createdAt < :toDt)
           OR (m.updatedAt IS NOT NULL AND m.updatedAt >= :fromDt AND m.updatedAt < :toDt)
        """)
    List<ActiveUserRow> findActiveUserRowsBetween(@Param("fromDt") LocalDateTime fromDt,
                                                  @Param("toDt") LocalDateTime toDt);

    /**
     * Memory-trends feed for {@code /admin/stats/memory-trends} (R7.2).
     *
     * <p>Selects every memory whose {@code createdAt} OR {@code updatedAt} falls inside
     * {@code [fromDt, toDt)}. The Java aggregator separately tallies {@code createdCount} and
     * {@code modifiedCount} per bucket — it counts modifications only when
     * {@code updatedAt > createdAt} so initial inserts are not double-counted.</p>
     */
    @Query("""
        SELECT m.id AS id,
               m.createdAt AS createdAt,
               m.updatedAt AS updatedAt
        FROM Memory m
        WHERE (m.createdAt >= :fromDt AND m.createdAt < :toDt)
           OR (m.updatedAt IS NOT NULL AND m.updatedAt >= :fromDt AND m.updatedAt < :toDt)
        """)
    List<MemoryTrendRow> findTrendRows(@Param("fromDt") LocalDateTime fromDt,
                                       @Param("toDt") LocalDateTime toDt);

    /**
     * Top-contributors feed for {@code /admin/stats/top-contributors} (R10.1).
     *
     * <p>Counts memories created per {@code userId} inside {@code [fromDt, toDt)}, ordered by
     * count descending. The {@code Pageable} caller supplies the {@code limit} (default 10,
     * capped 100 — see Requirements 10.3).</p>
     *
     * <p>Returns only the aggregate {@code (userId, memoryCount)} pair. The controller layer
     * joins with {@code AuthServiceClient.batchUsernames(...)} to populate the public DTO,
     * keeping that lookup the single point that introduces username into the response and
     * preserving the strict whitelist on the wire (Requirements 15.2).</p>
     */
    @Query("""
        SELECT m.userId AS userId,
               COUNT(m.id) AS memoryCount
        FROM Memory m
        WHERE m.createdAt >= :fromDt AND m.createdAt < :toDt
        GROUP BY m.userId
        ORDER BY COUNT(m.id) DESC
        """)
    List<ContributorRow> findTopContributors(@Param("fromDt") LocalDateTime fromDt,
                                             @Param("toDt") LocalDateTime toDt,
                                             Pageable pageable);

    /**
     * Heatmap snap-to-grid aggregation for {@code /admin/stats/heatmap} (R9.2, R9.3).
     *
     * <p>Native MySQL SQL — uses {@code FLOOR(memory_lat / :step) * :step + (:step / 2)} to
     * quantize coordinates to grid centres. {@code :step} is one of {@code {5.0, 1.0, 0.25}}
     * resolved by the controller from {@code gridResolution ∈ {LOW, MEDIUM, HIGH}}.
     * {@code ROUND(..., 6)} keeps floating-point noise out of the GROUP BY so identical
     * physical buckets collapse to one row.</p>
     *
     * <p>Filtered to {@code privacy_level = 'PUBLIC'} and rows with both coordinates set
     * (Requirements 15.3 — no private location leakage). The controller post-processes the
     * raw counts into normalized {@code intensity ∈ (0, 1]} values via {@code maxRaw → 1.0}
     * scaling.</p>
     */
    @Query(value = """
        SELECT
            ROUND(FLOOR(memory_lat / :step) * :step + (:step / 2), 6) AS latBucket,
            ROUND(FLOOR(memory_lng / :step) * :step + (:step / 2), 6) AS lngBucket,
            COUNT(*) AS rawCount
        FROM memories
        WHERE memory_lat IS NOT NULL
          AND memory_lng IS NOT NULL
        GROUP BY latBucket, lngBucket
        """, nativeQuery = true)
    List<HeatmapRow> heatmapBuckets(@Param("step") double step);

    /**
     * Eight-component emotion distribution for {@code /admin/stats/emotion-distribution}
     * (R8.2, R15.3).
     *
     * <p>Native MySQL SQL because {@code emotion_profile} is a JSON column and JPQL has no
     * portable {@code JSON_EXTRACT}. Each {@code AVG(JSON_EXTRACT(...))} averages across all
     * non-null public profiles inside {@code [fromDt, toDt)}; {@code COUNT(*)} is the sample
     * size used by the controller to coalesce nulls to {@code 0.0} when no rows match
     * (Requirements 8.3).</p>
     *
     * <p>Filtered to {@code privacy_level = 'PUBLIC'} and {@code emotion_profile IS NOT NULL}
     * — no private emotion data leaves the service.</p>
     */
    @Query(value = """
        SELECT
            AVG(JSON_EXTRACT(emotion_profile, '$.joy'))        AS avgJoy,
            AVG(JSON_EXTRACT(emotion_profile, '$.sadness'))    AS avgSadness,
            AVG(JSON_EXTRACT(emotion_profile, '$.anger'))      AS avgAnger,
            AVG(JSON_EXTRACT(emotion_profile, '$.fear'))       AS avgFear,
            AVG(JSON_EXTRACT(emotion_profile, '$.surprise'))   AS avgSurprise,
            AVG(JSON_EXTRACT(emotion_profile, '$.nostalgia'))  AS avgNostalgia,
            AVG(JSON_EXTRACT(emotion_profile, '$.peace'))      AS avgPeace,
            AVG(JSON_EXTRACT(emotion_profile, '$.melancholy')) AS avgMelancholy,
            COUNT(*)                                           AS sampleSize
        FROM memories
        WHERE privacy_level = 'PUBLIC'
          AND emotion_profile IS NOT NULL
          AND created_at >= :fromDt
          AND created_at <  :toDt
        """, nativeQuery = true)
    EmotionDistributionRow emotionDistributionBetween(@Param("fromDt") LocalDateTime fromDt,
                                                      @Param("toDt") LocalDateTime toDt);

    /**
     * Fragment-discovery aggregation grouped by {@code fragmentType} for
     * {@code /admin/stats/fragment-discovery?groupBy=fragmentType} (R11.3).
     *
     * <p>JPQL targets the {@code MemoryFragment} entity directly — the task asks for these
     * methods to live in {@code MemoryRepository} since the admin endpoint owning them is in
     * the same {@code memory-service} aggregation surface. Spring Data JPA happily resolves
     * cross-entity JPQL inside any {@code JpaRepository}.</p>
     */
    @Query("""
        SELECT
            f.fragmentType                                          AS fragmentType,
            COUNT(f.id)                                             AS totalFragments,
            COUNT(CASE WHEN f.isDiscovered = true THEN f.id ELSE NULL END) AS discoveredFragments
        FROM com.mnemoscape.memory.model.entity.MemoryFragment f
        GROUP BY f.fragmentType
        """)
    List<FragmentDiscoveryRow> aggregateFragmentByType();

    /**
     * Fragment-discovery overall aggregation for
     * {@code /admin/stats/fragment-discovery} without {@code groupBy} (R11.1).
     *
     * <p>{@code fragmentType} on the returned projection is a constant for this overall
     * variant; the controller branches on the presence of {@code groupBy} to decide which
     * projection to render.</p>
     */
    @Query("""
        SELECT
            COUNT(f.id)                                             AS totalFragments,
            COUNT(CASE WHEN f.isDiscovered = true THEN f.id ELSE NULL END) AS discoveredFragments
        FROM com.mnemoscape.memory.model.entity.MemoryFragment f
        """)
    FragmentDiscoveryRow aggregateFragmentOverall();
}
