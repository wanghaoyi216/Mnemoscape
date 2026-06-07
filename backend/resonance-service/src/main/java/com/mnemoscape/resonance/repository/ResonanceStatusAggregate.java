package com.mnemoscape.resonance.repository;

/**
 * Projection for the {@code resonance-overview} admin aggregation
 * (admin-dashboard Requirements 12.1).
 *
 * <p><b>Why interface, not record</b>: Spring Data JPA's native-query path
 * binds result columns by accessor method names on a projection interface;
 * it does NOT auto-bind to record components. Hibernate 6 returns a generic
 * {@code Tuple} for native results, and {@link org.springframework.data.jpa.repository.Query}
 * with {@code nativeQuery=true} only knows how to project that {@code Tuple}
 * onto an interface (or onto a {@code Class<?>} via {@code @SqlResultSetMapping}).
 * Using a record here historically caused {@code 500 ConverterNotFoundException}
 * on the resonance-overview endpoint.
 *
 * <p>The native query in
 * {@link ResonanceSpaceRepository#aggregateByStatus()} aliases its columns
 * to match these accessor names exactly:
 * <pre>
 *   SELECT status AS status,
 *          COUNT(*) AS count,
 *          AVG(similarity_score) AS averageScore
 *   FROM resonance_spaces
 *   GROUP BY status
 * </pre>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>{@code count} is boxed to {@link Long} so MySQL's {@code BIGINT}
 *       binds without primitive-conversion grief on Hibernate 6.</li>
 *   <li>{@code averageScore} is boxed: {@code AVG(similarity_score)} returns
 *       {@code NULL} for an empty group (won't happen here in practice
 *       because rows only exist when count > 0, but the projection
 *       layer still needs the boxed type to handle null gracefully).</li>
 *   <li>{@code status} mirrors the {@code resonance_spaces.status VARCHAR(20)}
 *       column. The schema has no enum constraint and defaults to
 *       {@code "pending"}; callers must not assume a closed value set.</li>
 * </ul>
 */
public interface ResonanceStatusAggregate {

    String getStatus();

    Long getCount();

    Double getAverageScore();
}
