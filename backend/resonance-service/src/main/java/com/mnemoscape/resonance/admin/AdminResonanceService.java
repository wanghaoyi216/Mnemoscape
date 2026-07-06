package com.mnemoscape.resonance.admin;

import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.resonance.admin.config.AdminCacheConfig;
import com.mnemoscape.resonance.admin.dto.ResonanceOverview;
import com.mnemoscape.resonance.admin.dto.ResonanceTopEdge;
import com.mnemoscape.resonance.model.entity.ResonanceSpace;
import com.mnemoscape.resonance.repository.ResonanceSpaceRepository;
import com.mnemoscape.resonance.repository.ResonanceStatusAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregation service feeding the resonance-service-side admin endpoints
 * (admin-dashboard task 8.2 / Requirements 12.1–12.3).
 *
 * <p>Two read-only endpoints flow through this class:
 * <ul>
 *   <li>{@code GET /api/v1/admin/stats/resonance-overview} — KPI overview
 *       (total edges, weighted average score, status breakdown). Cached
 *       for 120s in {@link AdminCacheConfig#CACHE_RESONANCE_OVERVIEW}.</li>
 *   <li>{@code GET /api/v1/admin/stats/resonance-top?limit=N} — top-N
 *       resonance edges sorted by score descending. Cached per {@code limit}
 *       value for 120s in {@link AdminCacheConfig#CACHE_RESONANCE_TOP}.</li>
 * </ul>
 *
 * <p>Both endpoints renamed entity columns to dashboard-canonical names
 * ({@code memoryAId} / {@code memoryBId} / {@code resonanceScore}) inside
 * {@link ResonanceTopEdge} per the requirement that the public DTO surface
 * is independent of the underlying ORM column names.
 *
 * <p>Privacy: neither endpoint surfaces memory titles, descriptions,
 * scene_data_url, or any column outside the strict whitelist documented in
 * each DTO record (R15.1 / R12.3).
 */
@Slf4j
@Service
public class AdminResonanceService {

    /** Default page size when no {@code limit} is supplied (R12.2). */
    static final int DEFAULT_LIMIT = 20;

    /** Hard cap on the {@code limit} parameter (R12.2 / R10.3 mirror). */
    static final int MAX_LIMIT = 100;

    private final ResonanceSpaceRepository repository;

    public AdminResonanceService(ResonanceSpaceRepository repository) {
        this.repository = repository;
    }

    /**
     * Aggregate the resonance KPI overview.
     *
     * <p>Algorithm (matches design.md §"#### resonance-service · /admin/**
     * 端点"):
     * <ol>
     *   <li>Run a single native group-by query over {@code resonance_spaces}
     *       returning one row per distinct status with the count and average
     *       score.</li>
     *   <li>Sum the counts to derive {@code totalEdges}.</li>
     *   <li>Compute the count-weighted mean of the per-status averages to
     *       derive the global {@code averageScore}; falls back to {@code 0.0}
     *       when {@code totalEdges == 0} so callers don't have to special-case
     *       the empty database.</li>
     *   <li>Project the rows into a stable insertion-ordered
     *       {@code Map<status, count>}.</li>
     * </ol>
     *
     * <p>Iteration order of the breakdown map is insertion order of the SQL
     * results (no explicit {@code ORDER BY status} on the query) — the front
     * end sorts visually anyway, and a stable native ordering is sufficient
     * for byte-level cache stability because Jackson {@code ORDER_MAP_ENTRIES_BY_KEYS}
     * is enabled in the cache serializer.
     */
    @Cacheable(cacheNames = AdminCacheConfig.CACHE_RESONANCE_OVERVIEW, sync = true)
    public ResonanceOverview overview() {
        List<ResonanceStatusAggregate> rows = repository.aggregateByStatus();
        if (rows == null || rows.isEmpty()) {
            return new ResonanceOverview(0L, 0.0, Map.of());
        }

        long totalEdges = 0L;
        double weightedSum = 0.0;
        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (ResonanceStatusAggregate row : rows) {
            // Spring Data interface projection — use getXxx() accessors.
            // Boxed Long defends against unlikely null counts; the whole
            // status group is dropped on null rather than crashing.
            Long countBox = row.getCount();
            long count = countBox == null ? 0L : countBox;
            totalEdges += count;
            Double avg = row.getAverageScore();
            if (avg != null) {
                weightedSum += avg * count;
            }
            // Coerce a null status (defensive: the column is non-null in
            // production schemas, but legacy rows may exist) into the literal
            // string "null" so the breakdown map remains a typed Map<String, Long>.
            String status = row.getStatus();
            breakdown.put(status == null ? "null" : status, count);
        }
        double averageScore = totalEdges == 0 ? 0.0 : weightedSum / totalEdges;
        return new ResonanceOverview(totalEdges, averageScore, breakdown);
    }

    /**
     * Top resonance edges ordered by similarity score descending.
     *
     * <p>{@code limit} parsing rules:
     * <ul>
     *   <li>{@code null} or blank → {@link #DEFAULT_LIMIT}.</li>
     *   <li>Non-numeric → 400 {@code INVALID_LIMIT}.</li>
     *   <li>Zero or negative → 400 {@code INVALID_LIMIT}.</li>
     *   <li>{@code > MAX_LIMIT} → silently capped at {@link #MAX_LIMIT}
     *       (R10.3 / R12.2 — "values above 100 capped").</li>
     * </ul>
     *
     * <p>The cache key embeds the resolved limit value so {@code ?limit=20}
     * and {@code ?limit=21} have separate cache entries (R14.1).
     *
     * @param limitRaw raw {@code limit} query string value; null = use default
     * @return resonance edges ordered by score descending, projected into the
     *         strict-whitelist DTO
     * @throws BizException 400 {@code INVALID_LIMIT} for non-positive or
     *                       non-numeric input
     */
    @Cacheable(
            cacheNames = AdminCacheConfig.CACHE_RESONANCE_TOP,
            key = "T(com.mnemoscape.resonance.admin.AdminResonanceService).cacheKey(#limitRaw)",
            sync = true)
    public List<ResonanceTopEdge> topEdges(String limitRaw) {
        int limit = parseLimit(limitRaw);
        List<ResonanceSpace> rows = repository.findAllOrderBySimilarityScoreDesc(
                PageRequest.of(0, limit));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<ResonanceTopEdge> out = new ArrayList<>(rows.size());
        for (ResonanceSpace r : rows) {
            out.add(new ResonanceTopEdge(
                    r.getMemoryId1(),
                    r.getMemoryId2(),
                    r.getSimilarityScore() == null ? 0.0 : r.getSimilarityScore(),
                    r.getStatus(),
                    toOffsetDateTime(r.getCreatedAt())));
        }
        return Collections.unmodifiableList(out);
    }

    /** SpEL-friendly cache key composer for {@link #topEdges}. */
    public static String cacheKey(String limitRaw) {
        return limitRaw == null ? "default" : limitRaw;
    }

    private static int parseLimit(String limitRaw) {
        if (limitRaw == null || limitRaw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(limitRaw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(400, "INVALID_LIMIT");
        }
        if (parsed <= 0) {
            throw new BizException(400, "INVALID_LIMIT");
        }
        return Math.min(parsed, MAX_LIMIT);
    }

    /**
     * Convert the entity's {@link LocalDateTime} (the platform stores
     * timestamps as UTC without an explicit zone) into an
     * {@link OffsetDateTime} suitable for the dashboard's ISO-8601 wire form.
     */
    private static OffsetDateTime toOffsetDateTime(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return ldt.atOffset(ZoneOffset.UTC);
    }

    // ============================================================ 管理后台 CRUD（事务由 Service 承担）

    /** 单条改状态:校验状态合法性 + 持久化。statusRaw 为 null/blank 时不改。 */
    @Transactional
    public ResonanceSpace patchStatus(String id, String statusRaw) {
        ResonanceSpace r = repository.findById(id)
                .orElseThrow(() -> new BizException(404, "RESONANCE_NOT_FOUND"));
        if (statusRaw != null && !statusRaw.isBlank()) {
            String allowed = statusRaw.trim().toLowerCase();
            if (!allowed.equals("pending") && !allowed.equals("accepted")
                    && !allowed.equals("rejected") && !allowed.equals("archived")) {
                throw new BizException(400, "INVALID_STATUS");
            }
            r.setStatus(allowed);
            repository.save(r);
        }
        return r;
    }

    /** 单条删除。 */
    @Transactional
    public void deleteOne(String id) {
        if (!repository.existsById(id)) {
            throw new BizException(404, "RESONANCE_NOT_FOUND");
        }
        repository.deleteById(id);
    }

    /** 批量删除:单条失败计入 failed 列表,不中断整批。 */
    @Transactional
    public BatchDeleteResult batchDelete(List<String> ids) {
        int deleted = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            try {
                repository.deleteById(id);
                deleted++;
            } catch (Exception e) {
                log.warn("[admin] batch-delete resonance failed for {}: {}", id, e.toString());
                failed.add(id);
            }
        }
        return new BatchDeleteResult(deleted, failed);
    }

    /** 批量改状态:校验状态合法性,逐条 findById+setStatus+save,返回更新数。 */
    @Transactional
    public BatchStatusResult batchStatus(List<String> ids, String status) {
        String s = status.trim().toLowerCase();
        if (!s.equals("pending") && !s.equals("accepted") && !s.equals("rejected") && !s.equals("archived")) {
            throw new BizException(400, "INVALID_STATUS");
        }
        int updated = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            var opt = repository.findById(id);
            if (opt.isPresent()) {
                ResonanceSpace r = opt.get();
                r.setStatus(s);
                repository.save(r);
                updated++;
            }
        }
        return new BatchStatusResult(updated, s);
    }

    public record BatchDeleteResult(int deleted, List<String> failed) {}
    public record BatchStatusResult(int updated, String status) {}
}
