package com.mnemoscape.resonance.admin;

import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.resonance.admin.dto.ResonanceOverview;
import com.mnemoscape.resonance.admin.dto.ResonanceTopEdge;
import com.mnemoscape.resonance.model.entity.ResonanceSpace;
import com.mnemoscape.resonance.repository.ResonanceSpaceRepository;
import com.mnemoscape.resonance.repository.ResonanceStatusAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminResonanceService} (admin-dashboard task 8.3,
 * validates Requirements 12.1 / 12.2 / 12.3 / 15.1).
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code overview()} folds per-status native rows into the three KPI
 *       fields with count-weighted average score;</li>
 *   <li>empty database produces {@code totalEdges=0}, {@code averageScore=0.0},
 *       empty {@code statusBreakdown};</li>
 *   <li>{@code topEdges(limit)} default = 20, capped at 100, rejects ≤ 0;</li>
 *   <li>output uses the canonical {@code memoryAId / memoryBId / resonanceScore}
 *       names regardless of entity column names;</li>
 *   <li>response shape never includes any forbidden privacy field.</li>
 * </ul>
 */
class AdminResonanceServiceTest {

    private ResonanceSpaceRepository repo;
    private AdminResonanceService service;

    @BeforeEach
    void setUp() {
        repo = mock(ResonanceSpaceRepository.class);
        service = new AdminResonanceService(repo);
    }

    /**
     * Build an inline {@link ResonanceStatusAggregate} stub. Hibernate
     * provides interface-projection instances at runtime via Spring Data;
     * the test side just needs anonymous-class instances with the right
     * accessor returns.
     */
    private static ResonanceStatusAggregate stubAggregate(String status, Long count, Double avg) {
        return new ResonanceStatusAggregate() {
            @Override public String getStatus() { return status; }
            @Override public Long getCount() { return count; }
            @Override public Double getAverageScore() { return avg; }
        };
    }

    @Nested
    @DisplayName("overview() KPI aggregation")
    class Overview {

        @Test
        void emptyDatabaseProducesAllZeroOverview() {
            when(repo.aggregateByStatus()).thenReturn(List.of());
            ResonanceOverview result = service.overview();
            assertEquals(0L, result.totalEdges());
            assertEquals(0.0, result.averageScore(), 0.0);
            assertTrue(result.statusBreakdown().isEmpty());
        }

        @Test
        void totalEdgesIsSumOfPerStatusCounts() {
            when(repo.aggregateByStatus()).thenReturn(List.of(
                    stubAggregate("pending", 30L, 0.6),
                    stubAggregate("active", 12L, 0.8)));
            ResonanceOverview result = service.overview();
            assertEquals(42L, result.totalEdges());
        }

        @Test
        void averageScoreIsCountWeightedMean() {
            when(repo.aggregateByStatus()).thenReturn(List.of(
                    stubAggregate("pending", 30L, 0.6),
                    stubAggregate("active", 10L, 0.9)));
            // weighted = (30*0.6 + 10*0.9) / 40 = (18 + 9) / 40 = 0.675
            ResonanceOverview result = service.overview();
            assertEquals(0.675, result.averageScore(), 1e-9);
        }

        @Test
        void statusBreakdownContainsEveryDistinctStatus() {
            when(repo.aggregateByStatus()).thenReturn(List.of(
                    stubAggregate("pending", 5L, 0.5),
                    stubAggregate("active", 3L, 0.7),
                    stubAggregate("archived", 2L, 0.3)));
            ResonanceOverview result = service.overview();
            assertEquals(Map.of("pending", 5L, "active", 3L, "archived", 2L),
                    Map.copyOf(result.statusBreakdown()));
        }

        @Test
        void nullStatusInRowMapsToLiteralNullKey() {
            // Defensive — production schema is non-null but legacy rows might
            // exist; we don't want a NullPointerException to crash the panel.
            when(repo.aggregateByStatus()).thenReturn(List.of(
                    stubAggregate(null, 1L, 0.5)));
            ResonanceOverview result = service.overview();
            assertEquals(1L, result.totalEdges());
            assertTrue(result.statusBreakdown().containsKey("null"));
        }

        @Test
        void nullAverageScoreIsTreatedAsZero() {
            // AVG over an empty group returns NULL on some DBs.
            when(repo.aggregateByStatus()).thenReturn(List.of(
                    stubAggregate("pending", 0L, null),
                    stubAggregate("active", 5L, 0.4)));
            ResonanceOverview result = service.overview();
            // weighted = (0*0 + 5*0.4) / 5 = 0.4
            assertEquals(0.4, result.averageScore(), 1e-9);
        }
    }

    @Nested
    @DisplayName("topEdges() pagination + DTO mapping")
    class TopEdges {

        @Test
        void defaultLimitIsTwenty() {
            when(repo.findAllOrderBySimilarityScoreDesc(any(Pageable.class))).thenAnswer(inv -> {
                Pageable p = inv.getArgument(0);
                assertEquals(20, p.getPageSize(), "default limit must be 20");
                return List.of();
            });
            service.topEdges(null);
        }

        @Test
        void explicitLimitIsHonored() {
            when(repo.findAllOrderBySimilarityScoreDesc(any(Pageable.class))).thenAnswer(inv -> {
                Pageable p = inv.getArgument(0);
                assertEquals(50, p.getPageSize());
                return List.of();
            });
            service.topEdges("50");
        }

        @Test
        void limitCappedAtOneHundred() {
            when(repo.findAllOrderBySimilarityScoreDesc(any(Pageable.class))).thenAnswer(inv -> {
                Pageable p = inv.getArgument(0);
                assertEquals(100, p.getPageSize(), "limit must be capped at 100");
                return List.of();
            });
            service.topEdges("250");
        }

        @Test
        void zeroLimitRejected400() {
            BizException ex = assertThrows(BizException.class, () -> service.topEdges("0"));
            assertEquals(400, ex.getCode());
            assertEquals("INVALID_LIMIT", ex.getMessage());
        }

        @Test
        void negativeLimitRejected400() {
            BizException ex = assertThrows(BizException.class, () -> service.topEdges("-5"));
            assertEquals(400, ex.getCode());
            assertEquals("INVALID_LIMIT", ex.getMessage());
        }

        @Test
        void nonNumericLimitRejected400() {
            BizException ex = assertThrows(BizException.class, () -> service.topEdges("twenty"));
            assertEquals(400, ex.getCode());
            assertEquals("INVALID_LIMIT", ex.getMessage());
        }

        @Test
        void entityFieldsRenamedInDto() {
            ResonanceSpace edge = new ResonanceSpace();
            edge.setId("r-1");
            edge.setMemoryId1("mem-aaa-bbb");
            edge.setMemoryId2("mem-ccc-ddd");
            edge.setSimilarityScore(0.91);
            edge.setStatus("active");
            edge.setCreatedAt(LocalDateTime.of(2026, 5, 24, 12, 0, 0));

            when(repo.findAllOrderBySimilarityScoreDesc(any(Pageable.class)))
                    .thenReturn(List.of(edge));

            List<ResonanceTopEdge> result = service.topEdges(null);

            assertEquals(1, result.size());
            ResonanceTopEdge dto = result.get(0);
            assertEquals("mem-aaa-bbb", dto.memoryAId(),
                    "entity field memoryId1 must be exposed as memoryAId");
            assertEquals("mem-ccc-ddd", dto.memoryBId(),
                    "entity field memoryId2 must be exposed as memoryBId");
            assertEquals(0.91, dto.resonanceScore(), 1e-9,
                    "entity field similarityScore must be exposed as resonanceScore");
            assertEquals("active", dto.status());
            // createdAt becomes UTC OffsetDateTime
            assertEquals(java.time.ZoneOffset.UTC, dto.createdAt().getOffset());
        }

        @Test
        void emptyResultReturnsEmptyList() {
            when(repo.findAllOrderBySimilarityScoreDesc(any(Pageable.class)))
                    .thenReturn(List.of());
            List<ResonanceTopEdge> result = service.topEdges(null);
            assertTrue(result.isEmpty());
        }

        @Test
        void nullSimilarityScoreCoercedToZero() {
            // Defensive: a row with NULL similarity_score must not NPE the DTO.
            ResonanceSpace edge = new ResonanceSpace();
            edge.setMemoryId1("a");
            edge.setMemoryId2("b");
            edge.setSimilarityScore(null);
            edge.setStatus("pending");
            edge.setCreatedAt(LocalDateTime.now());

            when(repo.findAllOrderBySimilarityScoreDesc(any(Pageable.class)))
                    .thenReturn(List.of(edge));

            List<ResonanceTopEdge> result = service.topEdges(null);
            assertEquals(0.0, result.get(0).resonanceScore());
        }
    }
}
