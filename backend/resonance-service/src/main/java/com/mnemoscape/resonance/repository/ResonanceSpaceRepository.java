package com.mnemoscape.resonance.repository;

import com.mnemoscape.resonance.model.entity.ResonanceSpace;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ResonanceSpaceRepository extends JpaRepository<ResonanceSpace, String> {

    @Query("SELECT r FROM ResonanceSpace r WHERE r.memoryId1 = :memoryId OR r.memoryId2 = :memoryId")
    List<ResonanceSpace> findByMemoryId(String memoryId);

    /**
     * Admin aggregation feeding {@code GET /api/v1/admin/stats/resonance-overview}
     * (admin-dashboard Requirements 12.1, design.md §resonance-service · /admin/** 端点).
     *
     * <p>Per the design table, this is a single-pass native group-by over
     * {@code resonance_spaces}: one row per distinct {@code status} value
     * carrying the bucket count and the average similarity score. The service
     * layer folds the rows into
     * {@code ResonanceOverview(totalEdges, averageScore, statusBreakdown)} —
     * {@code totalEdges} is the sum of {@code count} across rows,
     * {@code averageScore} is the count-weighted average, and
     * {@code statusBreakdown} is the {@code status → count} map.</p>
     *
     * <p>Native (rather than JPQL) is intentional: {@code AVG(similarity_score)}
     * over a non-null DOUBLE column delegates the arithmetic to MySQL, and the
     * column aliases match the record component names so Hibernate can bind
     * the {@code Tuple} result into {@link ResonanceStatusAggregate} directly.</p>
     */
    @Query(value = """
            SELECT status        AS status,
                   COUNT(*)      AS count,
                   AVG(similarity_score) AS averageScore
            FROM resonance_spaces
            GROUP BY status
            """, nativeQuery = true)
    List<ResonanceStatusAggregate> aggregateByStatus();

    /**
     * Admin query feeding {@code GET /api/v1/admin/stats/resonance-top?limit=N}
     * (admin-dashboard Requirements 12.2, design.md §resonance-service · /admin/** 端点).
     *
     * <p>Returns resonance edges ordered by {@code similarityScore} descending
     * so the controller can pass {@code Pageable.ofSize(limit)} to apply the
     * {@code N}-default-20-capped-100 limit. The DTO mapping (renaming
     * {@code memoryId1/memoryId2/similarityScore} to
     * {@code memoryAId/memoryBId/resonanceScore} per Requirement 12.3) happens
     * in the service layer; this method returns the raw entity so other
     * admin views can reuse it without a second query.</p>
     */
    @Query("SELECT r FROM ResonanceSpace r ORDER BY r.similarityScore DESC")
    List<ResonanceSpace> findAllOrderBySimilarityScoreDesc(Pageable pageable);
}
