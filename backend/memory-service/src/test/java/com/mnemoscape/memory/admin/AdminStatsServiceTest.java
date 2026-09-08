package com.mnemoscape.memory.admin;

import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.memory.admin.dto.ActiveUserBucket;
import com.mnemoscape.memory.admin.dto.ContributorRow;
import com.mnemoscape.memory.admin.dto.EmotionDistribution;
import com.mnemoscape.memory.admin.dto.EmotionDistributionRow;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryByType;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryOverall;
import com.mnemoscape.memory.admin.dto.FragmentDiscoveryRow;
import com.mnemoscape.memory.admin.dto.MemoryTrendBucket;
import com.mnemoscape.memory.admin.dto.MemoryTrendRow;
import com.mnemoscape.memory.client.AuthServiceClient;
import com.mnemoscape.memory.client.dto.BatchUsernamesRequest;
import com.mnemoscape.memory.client.dto.BatchUsernamesResponse;
import com.mnemoscape.memory.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminStatsService} covering admin-dashboard tasks
 * 7.10 (memory-trends bucket counting), 7.12 (emotion empty-set behaviour),
 * 7.18 (top-contributors degraded path), and 7.20 (fragment-discovery
 * zero-divisor behaviour).
 *
 * <p>All tests are hermetic — Spring is never started, the repository and
 * Feign client are Mockito mocks. This keeps the test suite fast (< 1s per
 * file) and lets the assertions focus on the service's own bucket / count /
 * fallback algorithms.
 */
class AdminStatsServiceTest {

    private MemoryRepository repo;
    private AuthServiceClient authClient;
    private AdminStatsService service;

    @BeforeEach
    void setUp() {
        repo = mock(MemoryRepository.class);
        authClient = mock(AuthServiceClient.class);
        service = new AdminStatsService(repo);
        service.setAuthServiceClient(authClient);
    }

    // ---------- Task 7.10 — memory-trends bucket counting ----------

    @Nested
    @DisplayName("memory-trends bucket counting (task 7.10)")
    class MemoryTrendsBucketing {

        @Test
        void countsCreatedAndModifiedSeparately() {
            // Window: 2026-05-22 ~ 2026-05-24 (3 daily buckets)
            // Constructed rows:
            //   - 3 created on 2026-05-22 (createdAt=updatedAt — initial inserts)
            //   - 2 created on 2026-05-23 BUT updatedAt > createdAt also on 2026-05-23
            //     → counts both as created AND modified
            List<MemoryTrendRow> rows = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                LocalDateTime t = LocalDateTime.of(2026, 5, 22, 10, i);
                rows.add(stubTrendRow("m" + i, t, t)); // updatedAt == createdAt, no modification
            }
            for (int i = 0; i < 2; i++) {
                LocalDateTime t = LocalDateTime.of(2026, 5, 23, 10, i);
                LocalDateTime updated = t.plusHours(2);
                rows.add(stubTrendRow("n" + i, t, updated));
            }

            when(repo.findTrendRows(any(), any())).thenReturn(rows);

            List<MemoryTrendBucket> result = service.aggregateMemoryTrends(
                    "DAILY", "2026-05-22", "2026-05-24");

            assertEquals(3, result.size(), "expected 3 daily buckets");
            assertEquals(new MemoryTrendBucket("2026-05-22", 3L, 0L), result.get(0));
            assertEquals(new MemoryTrendBucket("2026-05-23", 2L, 2L), result.get(1));
            assertEquals(new MemoryTrendBucket("2026-05-24", 0L, 0L), result.get(2));
        }

        @Test
        void doesNotCountInitialInsertsAsModifications() {
            // updatedAt == createdAt → must be created-only.
            LocalDateTime t = LocalDateTime.of(2026, 5, 22, 10, 0);
            when(repo.findTrendRows(any(), any())).thenReturn(
                    List.of(stubTrendRow("m1", t, t)));

            List<MemoryTrendBucket> result = service.aggregateMemoryTrends(
                    "DAILY", "2026-05-22", "2026-05-22");
            assertEquals(1, result.size());
            assertEquals(1L, result.get(0).createdCount());
            assertEquals(0L, result.get(0).modifiedCount(),
                    "initial insert (updatedAt == createdAt) must not be counted as a modification");
        }

        @Test
        void invalidDimensionThrows400() {
            BizException ex = assertThrows(BizException.class,
                    () -> service.aggregateMemoryTrends("HOURLY", null, null));
            assertEquals(400, ex.getCode());
            assertEquals("INVALID_DIMENSION", ex.getMessage());
        }

        @Test
        void fromAfterToThrows400() {
            BizException ex = assertThrows(BizException.class,
                    () -> service.aggregateMemoryTrends("DAILY", "2026-05-25", "2026-05-22"));
            assertEquals(400, ex.getCode());
            assertEquals("INVALID_RANGE", ex.getMessage());
        }
    }

    // ---------- Task 7.12 — emotion-distribution empty-set behaviour ----------

    @Nested
    @DisplayName("emotion-distribution sample handling (task 7.12)")
    class EmotionDistribution8Comp {

        @Test
        void zeroSampleSizeYieldsAllZeroComponents() {
            // sampleSize = 0 → all 8 components must be 0.0 (R8.3)
            when(repo.emotionDistributionBetween(any(), any())).thenReturn(stubEmotionRow(
                    null, null, null, null, null, null, null, null, 0L));

            EmotionDistribution result = service.aggregateEmotionDistribution(null, null);

            assertEquals(0L, result.sampleSize());
            assertEquals(0.0, result.joy());
            assertEquals(0.0, result.sadness());
            assertEquals(0.0, result.anger());
            assertEquals(0.0, result.fear());
            assertEquals(0.0, result.surprise());
            assertEquals(0.0, result.nostalgia());
            assertEquals(0.0, result.peace());
            assertEquals(0.0, result.melancholy());
        }

        @Test
        void nullRowAlsoYieldsAllZero() {
            when(repo.emotionDistributionBetween(any(), any())).thenReturn(null);
            EmotionDistribution result = service.aggregateEmotionDistribution(null, null);
            assertEquals(0L, result.sampleSize());
            assertEquals(0.0, result.joy());
        }

        @Test
        void averagesArePassedThroughForNonEmptySamples() {
            when(repo.emotionDistributionBetween(any(), any())).thenReturn(stubEmotionRow(
                    0.30, 0.20, 0.05, 0.10, 0.15, 0.08, 0.07, 0.05, 1234L));

            EmotionDistribution result = service.aggregateEmotionDistribution(null, null);

            assertEquals(1234L, result.sampleSize());
            assertEquals(0.30, result.joy(), 1e-9);
            assertEquals(0.20, result.sadness(), 1e-9);
            assertEquals(0.05, result.anger(), 1e-9);
            assertEquals(0.10, result.fear(), 1e-9);
            assertEquals(0.15, result.surprise(), 1e-9);
            assertEquals(0.08, result.nostalgia(), 1e-9);
            assertEquals(0.07, result.peace(), 1e-9);
            assertEquals(0.05, result.melancholy(), 1e-9);
        }
    }

    // ---------- Task 7.18 — top-contributors degraded path ----------

    @Nested
    @DisplayName("top-contributors degraded path (task 7.18)")
    class TopContributorsDegradation {

        @Test
        void limitCappedAtOneHundred() {
            when(repo.findTopContributors(any(), any(), any())).thenAnswer(inv -> {
                Pageable p = inv.getArgument(2);
                assertEquals(100, p.getPageSize(), "limit must be capped at 100");
                return List.<ContributorRow>of();
            });
            service.aggregateTopContributors("250", null, null);
        }

        @Test
        void zeroLimitRejected400() {
            BizException ex = assertThrows(BizException.class,
                    () -> service.aggregateTopContributors("0", null, null));
            assertEquals(400, ex.getCode());
            assertEquals("INVALID_LIMIT", ex.getMessage());
        }

        @Test
        void negativeLimitRejected400() {
            BizException ex = assertThrows(BizException.class,
                    () -> service.aggregateTopContributors("-3", null, null));
            assertEquals(400, ex.getCode());
            assertEquals("INVALID_LIMIT", ex.getMessage());
        }

        @Test
        void nonNumericLimitRejected400() {
            BizException ex = assertThrows(BizException.class,
                    () -> service.aggregateTopContributors("ten", null, null));
            assertEquals(400, ex.getCode());
            assertEquals("INVALID_LIMIT", ex.getMessage());
        }

        @Test
        void feignFailureProducesDegradedResponseWithFallbackUsername() {
            when(repo.findTopContributors(any(), any(), any())).thenReturn(List.of(
                    stubContributorRow("user-id-aaaaaaaa-bbbb", 12L),
                    stubContributorRow("user-id-cccccccc-dddd", 7L)));
            // Feign throws — degraded path must NOT propagate.
            when(authClient.batchUsernames(any()))
                    .thenThrow(new RuntimeException("feign down"));

            var resp = service.aggregateTopContributors(null, null, null);

            assertTrue(resp.degraded(), "must mark response as degraded");
            assertEquals(List.of("auth-service username lookup failed"), resp.degradedReasons());
            assertEquals(2, resp.items().size());
            // Fallback username = first 8 chars of userId
            assertEquals("user-id-", resp.items().get(0).username());
            assertEquals("user-id-", resp.items().get(1).username());
            // Counts preserved
            assertEquals(12L, resp.items().get(0).memoryCount());
        }

        @Test
        void feignSuccessProducesNonDegradedResponseWithRealUsernames() {
            when(repo.findTopContributors(any(), any(), any())).thenReturn(List.of(
                    stubContributorRow("u-1", 12L),
                    stubContributorRow("u-2", 7L)));
            when(authClient.batchUsernames(any())).thenReturn(
                    ApiResponse.success(new BatchUsernamesResponse(
                            Map.of("u-1", "alice", "u-2", "bob"))));

            var resp = service.aggregateTopContributors(null, null, null);

            assertFalse(resp.degraded());
            assertEquals(2, resp.items().size());
            assertEquals("alice", resp.items().get(0).username());
            assertEquals("bob", resp.items().get(1).username());
        }

        @Test
        void missingUsernameInFeignResponseFallsBackPerEntry() {
            when(repo.findTopContributors(any(), any(), any())).thenReturn(List.of(
                    stubContributorRow("u-known-aaaaaa", 5L),
                    stubContributorRow("u-missing-bbbb", 3L)));
            when(authClient.batchUsernames(any())).thenReturn(
                    ApiResponse.success(new BatchUsernamesResponse(
                            Map.of("u-known-aaaaaa", "alice"))));
            // u-missing-bbbb is not in the map.
            var resp = service.aggregateTopContributors(null, null, null);
            assertFalse(resp.degraded(),
                    "missing entry for one user is not a degradation, only Feign failure is");
            assertEquals("alice", resp.items().get(0).username());
            assertEquals("u-missin", resp.items().get(1).username(),
                    "missing username must fall back to userId.substring(0, 8)");
        }

        @Test
        void requestPayloadCarriesAllUserIds() {
            when(repo.findTopContributors(any(), any(), any())).thenReturn(List.of(
                    stubContributorRow("u-1", 5L),
                    stubContributorRow("u-2", 3L),
                    stubContributorRow("u-3", 1L)));
            org.mockito.ArgumentCaptor<BatchUsernamesRequest> cap =
                    org.mockito.ArgumentCaptor.forClass(BatchUsernamesRequest.class);
            when(authClient.batchUsernames(cap.capture())).thenReturn(
                    ApiResponse.success(new BatchUsernamesResponse(Map.of())));

            service.aggregateTopContributors(null, null, null);

            assertEquals(List.of("u-1", "u-2", "u-3"), cap.getValue().userIds(),
                    "Feign request must contain ALL contributors in original order");
        }
    }

    // ---------- Task 7.20 — fragment-discovery aggregations ----------

    @Nested
    @DisplayName("fragment-discovery aggregation (task 7.20)")
    class FragmentDiscovery {

        @Test
        void totalZeroProducesRateZero() {
            when(repo.aggregateFragmentOverall()).thenReturn(stubFragmentRow(null, 0L, 0L));
            FragmentDiscoveryOverall result = service.aggregateFragmentDiscoveryOverall();
            assertEquals(0L, result.totalFragments());
            assertEquals(0L, result.discoveredFragments());
            assertEquals(0.0, result.discoveryRate(), 0.0,
                    "discoveryRate must be 0.0 when totalFragments == 0 (R11.2)");
        }

        @Test
        void overallRateMatchesDiscoveredOverTotal() {
            when(repo.aggregateFragmentOverall()).thenReturn(stubFragmentRow(null, 80L, 20L));
            FragmentDiscoveryOverall result = service.aggregateFragmentDiscoveryOverall();
            assertEquals(0.25, result.discoveryRate(), 1e-9);
        }

        @Test
        void byTypeReturnsOneRowPerType() {
            when(repo.aggregateFragmentByType()).thenReturn(List.of(
                    stubFragmentRow("forgotten_detail", 50L, 10L),
                    stubFragmentRow("emotion_flashback", 30L, 6L),
                    stubFragmentRow("scene_artifact", 20L, 0L)));

            List<FragmentDiscoveryByType> result = service.aggregateFragmentDiscoveryByType();

            assertEquals(3, result.size());
            assertEquals("forgotten_detail", result.get(0).fragmentType());
            assertEquals(0.2, result.get(0).discoveryRate(), 1e-9);
            assertEquals(0.0, result.get(2).discoveryRate(), 1e-9,
                    "by-type with totalFragments != 0 but discovered == 0 must yield rate=0");
        }

        @Test
        void byTypeWithZeroTotalProducesRateZero() {
            when(repo.aggregateFragmentByType()).thenReturn(List.of(
                    stubFragmentRow("rare_type", 0L, 0L)));
            List<FragmentDiscoveryByType> result = service.aggregateFragmentDiscoveryByType();
            assertEquals(1, result.size());
            assertEquals(0.0, result.get(0).discoveryRate(), 0.0);
        }

        @Test
        void emptyByTypeReturnsEmptyList() {
            when(repo.aggregateFragmentByType()).thenReturn(List.of());
            assertTrue(service.aggregateFragmentDiscoveryByType().isEmpty());
        }
    }

    // ---------- Helpers ----------

    private static MemoryTrendRow stubTrendRow(String id, LocalDateTime created, LocalDateTime updated) {
        return new MemoryTrendRow() {
            @Override public String getId() { return id; }
            @Override public LocalDateTime getCreatedAt() { return created; }
            @Override public LocalDateTime getUpdatedAt() { return updated; }
        };
    }

    private static EmotionDistributionRow stubEmotionRow(
            Double joy, Double sadness, Double anger, Double fear,
            Double surprise, Double nostalgia, Double peace, Double melancholy,
            long sampleSize) {
        return new EmotionDistributionRow() {
            @Override public Double getAvgJoy() { return joy; }
            @Override public Double getAvgSadness() { return sadness; }
            @Override public Double getAvgAnger() { return anger; }
            @Override public Double getAvgFear() { return fear; }
            @Override public Double getAvgSurprise() { return surprise; }
            @Override public Double getAvgNostalgia() { return nostalgia; }
            @Override public Double getAvgPeace() { return peace; }
            @Override public Double getAvgMelancholy() { return melancholy; }
            @Override public long getSampleSize() { return sampleSize; }
        };
    }

    private static ContributorRow stubContributorRow(String userId, long count) {
        return new ContributorRow() {
            @Override public String getUserId() { return userId; }
            @Override public Long getMemoryCount() { return count; }
        };
    }

    private static FragmentDiscoveryRow stubFragmentRow(String type, long total, long discovered) {
        return new FragmentDiscoveryRow() {
            @Override public String getFragmentType() { return type; }
            @Override public long getTotalFragments() { return total; }
            @Override public long getDiscoveredFragments() { return discovered; }
        };
    }
}
