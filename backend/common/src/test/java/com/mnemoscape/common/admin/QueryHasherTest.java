package com.mnemoscape.common.admin;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QueryHasher}
 * (admin-dashboard task 1.8 / validates Requirements 15.5).
 *
 * <p>The hasher must:
 * <ol>
 *   <li>produce identical output for identical inputs (determinism);</li>
 *   <li>be insensitive to map iteration order (canonicalisation);</li>
 *   <li>emit a stable {@code "unhashable"} sentinel when serialisation fails
 *       so audit logs never carry partial / leaking values;</li>
 *   <li>handle null / empty maps without throwing.</li>
 * </ol>
 */
class QueryHasherTest {

    @Test
    void identicalInputsProduceIdenticalHash() {
        Map<String, Object> a = Map.of("dimension", "DAILY", "from", "2026-01-01", "to", "2026-01-31");
        Map<String, Object> b = Map.of("dimension", "DAILY", "from", "2026-01-01", "to", "2026-01-31");
        assertEquals(QueryHasher.hash(a), QueryHasher.hash(b));
    }

    @Test
    void keyInsertionOrderDoesNotAffectHash() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("dimension", "WEEKLY");
        ordered.put("from", "2026-05-01");
        ordered.put("to", "2026-05-31");

        Map<String, Object> reverseOrdered = new LinkedHashMap<>();
        reverseOrdered.put("to", "2026-05-31");
        reverseOrdered.put("from", "2026-05-01");
        reverseOrdered.put("dimension", "WEEKLY");

        assertEquals(QueryHasher.hash(ordered), QueryHasher.hash(reverseOrdered),
                "QueryHasher must canonicalise key order");
    }

    @Test
    void treeMapAndHashMapAgree() {
        // Both Map types should produce the same canonical bytes once we sort
        // entries in QueryHasher.
        Map<String, Object> hashMap = Map.of("a", 1, "b", 2, "c", 3);
        Map<String, Object> treeMap = new TreeMap<>(hashMap);
        assertEquals(QueryHasher.hash(hashMap), QueryHasher.hash(treeMap));
    }

    @Test
    void differentValuesProduceDifferentHash() {
        Map<String, Object> a = Map.of("dimension", "DAILY");
        Map<String, Object> b = Map.of("dimension", "WEEKLY");
        assertNotEquals(QueryHasher.hash(a), QueryHasher.hash(b));
    }

    @Test
    void differentKeysProduceDifferentHash() {
        Map<String, Object> a = Map.of("dimension", "DAILY");
        Map<String, Object> b = Map.of("Dimension", "DAILY"); // different case
        assertNotEquals(QueryHasher.hash(a), QueryHasher.hash(b));
    }

    @Test
    void nullMapHashesToTheEmptyMapValue() {
        assertEquals(QueryHasher.hash(Map.of()), QueryHasher.hash(null));
    }

    @Test
    void emptyMapProducesStableNonEmptyHash() {
        String empty = QueryHasher.hash(Map.of());
        assertNotNull(empty);
        assertEquals(16, empty.length());
        assertEquals(empty, QueryHasher.hash(Collections.emptyMap()));
    }

    @Test
    void hashIsAlwaysSixteenLowerHexChars() {
        Map<String, Object> input = Map.of("dimension", "DAILY", "from", "2026-01-01");
        String hash = QueryHasher.hash(input);
        assertEquals(16, hash.length());
        assertTrue(hash.matches("[0-9a-f]{16}"),
                "expected 16-char lower-hex SHA-256 prefix, got: " + hash);
    }

    @Test
    void unserializableValueFallsBackToUnhashable() {
        // A self-referencing object is unserialisable for Jackson — provoke the
        // catch branch and verify we get the sentinel.
        Map<String, Object> bad = Map.of("self", new Unserializable());
        String hash = QueryHasher.hash(bad);
        assertEquals("unhashable", hash);
    }

    /** Throws when serialised; used to drive {@link QueryHasher} into its catch branch. */
    private static final class Unserializable {
        @com.fasterxml.jackson.annotation.JsonValue
        public String boom() {
            throw new RuntimeException("nope");
        }
    }
}
