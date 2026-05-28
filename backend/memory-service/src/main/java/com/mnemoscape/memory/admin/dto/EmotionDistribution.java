package com.mnemoscape.memory.admin.dto;

/**
 * Strict-whitelist response record for
 * {@code GET /api/v1/admin/stats/emotion-distribution} (admin-dashboard
 * task 7.11 / Requirements 8.1–8.4).
 *
 * <p>Carries the eight-component mean emotion vector across all PUBLIC
 * memories in the requested window plus the {@code sampleSize} used to
 * derive the means. When {@code sampleSize == 0} every component is
 * {@code 0.0} (Requirements 8.3) so the front-end's radar chart renders
 * an empty polygon instead of crashing on null components.
 *
 * <p>The eight components match the {@code emotion_profile} JSON keys
 * stored on the {@code memories} table:
 * {@code joy, sadness, anger, fear, surprise, nostalgia, peace, melancholy}.
 *
 * <p>Privacy boundary: this record exposes ONLY the aggregate means and the
 * sample size. No individual memory ids, titles, or descriptions are ever
 * surfaced (Requirement 15.1). Adding a field requires updating the design
 * document and the privacy-leak guard tests.
 */
public record EmotionDistribution(
        double joy,
        double sadness,
        double anger,
        double fear,
        double surprise,
        double nostalgia,
        double peace,
        double melancholy,
        long sampleSize) {
}
