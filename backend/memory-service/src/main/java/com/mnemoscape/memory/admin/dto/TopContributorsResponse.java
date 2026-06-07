package com.mnemoscape.memory.admin.dto;

import java.util.Collections;
import java.util.List;

/**
 * Envelope for {@code GET /api/v1/admin/stats/top-contributors} carrying
 * the contributor list along with the optional R18.1 degradation flags
 * (admin-dashboard task 7.17).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code items} — the strict-whitelist {@link TopContributor} list.</li>
 *   <li>{@code degraded} — {@code true} when at least one auxiliary lookup
 *       (today: auth-service batch-usernames) failed and the response is
 *       therefore a partial answer (Requirements 18.1).</li>
 *   <li>{@code degradedReasons} — short human-readable strings explaining
 *       which auxiliary failed; localized i18n keys live on the front end.</li>
 * </ul>
 *
 * <p>Distinct from the {@code overview} / {@code memory-trends} endpoints,
 * top-contributors uses an envelope record rather than a bare list because
 * the dashboard's degradation badge needs the {@code degraded} flag inside
 * the {@code data} payload (the outer {@code ApiResponse.code} stays
 * {@code 200} on degraded responses per design §Caching Strategy).
 */
public record TopContributorsResponse(
        List<TopContributor> items,
        boolean degraded,
        List<String> degradedReasons) {

    /**
     * Convenience constructor for the happy path: degraded = false, no reasons.
     */
    public static TopContributorsResponse ok(List<TopContributor> items) {
        return new TopContributorsResponse(items, false, Collections.emptyList());
    }

    /**
     * Convenience constructor for the partial-failure path.
     */
    public static TopContributorsResponse degraded(List<TopContributor> items, List<String> reasons) {
        return new TopContributorsResponse(items, true,
                reasons == null ? Collections.emptyList() : List.copyOf(reasons));
    }
}
