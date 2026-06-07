package com.mnemoscape.memory.admin.dto;

/**
 * Strict-whitelist response record for one point in
 * {@code GET /api/v1/admin/stats/heatmap?gridResolution} (admin-dashboard
 * task 7.13 / Requirements 9.1–9.4).
 *
 * <p>Each instance is the centre of a quantised lat/lon cell carrying a
 * normalised intensity:
 * <ul>
 *   <li>{@code lat} — cell centre latitude in degrees ({@code k * step + step/2})</li>
 *   <li>{@code lon} — cell centre longitude in degrees ({@code k' * step + step/2})</li>
 *   <li>{@code intensity} — relative density in {@code (0, 1]}; the cell
 *       with the maximum raw count maps to {@code 1.0}</li>
 * </ul>
 *
 * <p>Empty databases produce an empty list, not points with
 * {@code intensity = 0.0}. The deck.gl HexagonLayer handles either case
 * cleanly but excluding zero-density cells reduces wire size dramatically
 * for sparse data (Requirements 9.4).
 *
 * <p>Privacy boundary: only quantised coordinates and a derived intensity
 * leave the service. Memory ids, titles, descriptions, owner ids — none
 * are accessible through this response (Requirements 15.1 / 15.3).
 */
public record HeatmapPoint(double lat, double lon, double intensity) {
}
