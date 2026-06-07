package com.mnemoscape.memory.admin.dto;

/**
 * Spring Data JPA projection for the emotion-distribution query in
 * {@code /admin/stats/emotion-distribution}.
 *
 * <p>Backed by a single native SQL statement that pulls {@code AVG(JSON_EXTRACT(emotion_profile,
 * '$.&lt;component&gt;'))} for the eight fixed components plus a {@code COUNT(*)} sample size,
 * filtered to {@code privacy_level = 'PUBLIC'} and {@code emotion_profile IS NOT NULL}. See
 * {@code MemoryRepository#emotionDistributionBetween} for the SQL.</p>
 *
 * <p>The averages are typed as boxed {@code Double} because {@code AVG} of an empty set returns
 * {@code NULL}; the controller layer is expected to coalesce nulls to {@code 0.0} when
 * {@code sampleSize == 0} per Requirements 8.3.</p>
 *
 * <p>Validates: admin-dashboard Requirements 8.2.</p>
 */
public interface EmotionDistributionRow {

    Double getAvgJoy();

    Double getAvgSadness();

    Double getAvgAnger();

    Double getAvgFear();

    Double getAvgSurprise();

    Double getAvgNostalgia();

    Double getAvgPeace();

    Double getAvgMelancholy();

    long getSampleSize();
}
