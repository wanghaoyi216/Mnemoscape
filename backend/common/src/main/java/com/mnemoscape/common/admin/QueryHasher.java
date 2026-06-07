package com.mnemoscape.common.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes a stable, privacy-safe identifier for an admin endpoint query so it
 * can be embedded in {@code admin-audit} log entries without leaking raw
 * parameter values (Requirements 15.4, 15.5).
 *
 * <p>The hash is computed as follows:
 * <ol>
 *   <li>The supplied parameter map is copied into a {@link TreeMap} so that
 *       keys are iterated in alpha order.</li>
 *   <li>It is serialised to JSON with {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}
 *       enabled, producing a canonical byte form regardless of the caller's
 *       insertion order.</li>
 *   <li>SHA-256 is applied to the canonical bytes and the first 16 hex
 *       characters of the digest are returned.</li>
 * </ol>
 *
 * <p>Any failure during the pipeline (serialisation, digest, &hellip;) is
 * intentionally swallowed and the sentinel {@code "unhashable"} is returned —
 * we must never propagate a hashing failure up to the request thread, and we
 * must never echo the raw parameters back in the audit trail.
 *
 * <p>Callers are expected to populate the map with <em>query semantics only</em>
 * (e.g. {@code dimension}, {@code from}, {@code to}, {@code limit}). Authentication
 * headers ({@code Authorization}, {@code X-User-Id}, &hellip;) MUST NOT be passed
 * in.
 */
public final class QueryHasher {

    /**
     * Shared {@link ObjectMapper}. Configuring {@code ORDER_MAP_ENTRIES_BY_KEYS}
     * combined with the {@link TreeMap} wrapping in {@link #hash(Map)} guarantees
     * canonical, key-order-insensitive output.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Sentinel returned when canonical hashing fails for any reason. */
    private static final String UNHASHABLE = "unhashable";

    /** Number of hex characters retained from the SHA-256 digest. */
    private static final int HEX_PREFIX_LENGTH = 16;

    private QueryHasher() {
        // utility class
    }

    /**
     * Compute the canonical SHA-256 prefix hash for {@code params}.
     *
     * @param params query parameters; {@code null} is treated as an empty map
     * @return 16 lowercase hex characters from the SHA-256 digest of the
     *         canonical JSON, or {@code "unhashable"} if any step fails
     */
    public static String hash(Map<String, ?> params) {
        try {
            TreeMap<String, ?> canonical = new TreeMap<>(params == null ? Map.of() : params);
            byte[] bytes = MAPPER.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest).substring(0, HEX_PREFIX_LENGTH);
        } catch (Exception e) {
            // Never expose raw params on the failure path — return a stable sentinel instead.
            return UNHASHABLE;
        }
    }
}
