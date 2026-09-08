package com.mnemoscape.memory.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * R32: Neo4j write-path observability decorator.
 *
 * <p>Two meters, both tagged by {@code operation} (one of
 * {@code nodes} / {@code rels} / {@code graph_batch}) and {@code outcome}
 * ({@code success} | {@code failure}):
 * <ul>
 *   <li>{@code neo4j.write.duration} ({@link Timer}) — wall-clock time of
 *       one Cypher batch (UNWIND + MERGE).  Drives p95/p99 dashboards and
 *       the slow-batch alert at {@code > 500ms}.</li>
 *   <li>{@code neo4j.records.written} ({@link Counter}) — number of rows
 *       (nodes or relationships) actually accepted by Neo4j.  Failed
 *       batches are recorded with outcome=failure but a record count of
 *       zero, so dashboards can compute "attempted vs succeeded" rates.</li>
 * </ul>
 *
 * <p>Cardinality: only two tag dimensions, both bounded.  {@code operation}
 * is closed-set (5 values); {@code outcome} is closed-set (2 values).  We
 * deliberately do NOT add a {@code label} tag for the node label
 * (Memory / User / Person / …) — that would force a tag-set per label
 * and explode the meter count for services that have many label
 * combinations.
 */
@Component
public class Neo4jWriteMetrics {

    private static final Logger log = LoggerFactory.getLogger(Neo4jWriteMetrics.class);

    public static final String METRIC_WRITE_DURATION = "neo4j.write.duration";
    public static final String METRIC_RECORDS_WRITTEN = "neo4j.records.written";

    public static final String OP_NODES = "nodes";
    public static final String OP_RELS = "rels";
    public static final String OP_GRAPH_BATCH = "graph_batch";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private final MeterRegistry registry;

    @Autowired
    public Neo4jWriteMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");
    }

    /**
     * Record a single Neo4j write operation.
     *
     * @param operation   one of {@link #OP_NODES}, {@link #OP_RELS},
     *                    {@link #OP_GRAPH_BATCH}
     * @param records     number of records the batch tried to write
     *                    (0 if the call was skipped / short-circuited)
     * @param success     true if the Cypher call returned without throwing
     * @param startNanos  value of {@code System.nanoTime()} captured before
     *                    the Cypher call; the timer records the delta
     */
    public void record(String operation, long records, boolean success, long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        String outcome = success ? OUTCOME_SUCCESS : OUTCOME_FAILURE;
        Tags tags = Tags.of("operation", safe(operation), "outcome", outcome);
        try {
            Timer.builder(METRIC_WRITE_DURATION)
                    .description("Wall-clock duration of a Neo4j write batch")
                    .tags(tags)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
            // Failed batches are recorded with the same tag set so the
            // counter and timer are co-located in dashboards.  We still
            // increment the counter by `records` (0 on failure is fine —
            // it preserves the "attempted volume" semantics).
            Counter.builder(METRIC_RECORDS_WRITTEN)
                    .description("Records written to Neo4j per batch")
                    .tags(tags)
                    .register(registry)
                    .increment(records);
        } catch (Exception e) {
            // Never let a metrics failure cascade into a 5xx.
            log.debug("[Neo4jWriteMetrics] failed to record: {}", e.getMessage());
        }
    }

    /**
     * Convenience overload for the high-level entry point
     * {@code Neo4jBatchWriter.writeMemoryGraphBatch}: the batch already
     * returns a {@code WriteStats} object; we record one duration
     * measurement plus a node-counter and a rel-counter entry.
     */
    public void recordGraphBatch(long nodesWritten, long relsWritten,
                                 long elapsedMillis, boolean success) {
        long startNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(elapsedMillis);
        record(OP_GRAPH_BATCH, nodesWritten + relsWritten, success, startNanos);
    }

    private static String safe(String tag) {
        return (tag == null || tag.isEmpty()) ? "unknown" : tag;
    }
}
