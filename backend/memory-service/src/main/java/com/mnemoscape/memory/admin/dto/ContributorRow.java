package com.mnemoscape.memory.admin.dto;

/**
 * Spring Data JPA projection for the top-contributors query in
 * {@code /admin/stats/top-contributors}.
 *
 * <p>Each row holds an aggregated {@code (userId, memoryCount)} pair returned by
 * {@code MemoryRepository#findTopContributors} (JPQL with {@code GROUP BY userId} ordered by
 * {@code COUNT(id) DESC}). The controller layer joins this list with
 * {@code AuthServiceClient.batchUsernames(...)} to populate the public-facing
 * {@code TopContributor} record. <strong>This projection deliberately exposes nothing beyond
 * {@code userId} and {@code memoryCount}</strong> — the auth-service username lookup is the only
 * place the response gets enriched, and that contract is strictly whitelisted (no email,
 * passwordHash, etc.) per Requirements 10.4 / 15.2.</p>
 *
 * <p>Validates: admin-dashboard Requirements 10.1.</p>
 */
public interface ContributorRow {

    String getUserId();

    long getMemoryCount();
}
