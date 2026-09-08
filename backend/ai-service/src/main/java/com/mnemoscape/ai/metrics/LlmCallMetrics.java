package com.mnemoscape.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * R32: LLM call observability decorator.
 *
 * <p>Every outbound call to an upstream model (NVIDIA Integrate today, but
 * the API is provider-agnostic) is recorded under
 * {@code mnemoscape.llm.*} with two label dimensions:
 * <ul>
 *   <li>{@code model} — model identifier (e.g. {@code meta/llama-3.2-11b-vision-instruct},
 *       {@code minimaxai/minimax-m2.7}).  Cardinality is bounded by the small
 *       set of models wired into {@code mnemoscape.ai.upstream.*} in
 *       application.yml — never the raw prompt text.</li>
 *   <li>{@code outcome} — {@code success} | {@code timeout} | {@code upstream_error} |
 *       {@code rate_limited} | {@code quota_exceeded}.  Five fixed buckets;
 *       never the full exception message.</li>
 * </ul>
 *
 * <h2>Why a custom decorator instead of just counters/timers</h2>
 * <p>LLM calls happen in three different code paths (sync
 * {@code ChatReasoner.generateAnswer}, streaming
 * {@code ChatReasoner.streamAnswer}, vision
 * {@code VisionPreflightService}).  Centralising the timer/counter wiring in
 * a single decorator means we never forget a tag, never leak a model name
 * into a high-cardinality label, and never have to plumb
 * {@code MeterRegistry} through every call site.  The decorator is also
 * the natural place to capture first-token latency in the future without
 * touching the call sites.
 *
 * <h2>Thread safety</h2>
 * <p>{@link MeterRegistry} is thread-safe; the builder methods
 * ({@link Counter#builder(String)} etc.) are designed to be invoked from
 * hot paths.  We resolve the meter per call — Micrometer's registry
 * deduplicates by (name, tag set) so the only cost is a hash lookup, and
 * we avoid leaking meters if a new label combination is observed at
 * runtime.
 */
@Component
public class LlmCallMetrics {

    public static final String PREFIX = "mnemoscape.llm";

    /** Counter: number of LLM invocations, tagged by model + outcome. */
    public static final String METRIC_CALLS_TOTAL = PREFIX + ".calls.total";
    /** Timer: end-to-end LLM call duration, tagged by model + outcome. */
    public static final String METRIC_CALL_DURATION = PREFIX + ".call.duration";
    /** Counter: number of input tokens (prompt side). */
    public static final String METRIC_PROMPT_TOKENS = PREFIX + ".tokens.prompt";
    /** Counter: number of output tokens (completion side). */
    public static final String METRIC_COMPLETION_TOKENS = PREFIX + ".tokens.completion";

    /** Bounded set of outcome buckets.  Kept as a String constant to avoid
     *  building a new enum every call site, and to let callers pass
     *  lower-case string literals without surprises. */
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_TIMEOUT = "timeout";
    public static final String OUTCOME_UPSTREAM_ERROR = "upstream_error";
    public static final String OUTCOME_RATE_LIMITED = "rate_limited";
    public static final String OUTCOME_QUOTA_EXCEEDED = "quota_exceeded";

    private final MeterRegistry registry;

    @Autowired
    public LlmCallMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");
    }

    /**
     * Start timing an LLM call.  Callers should
     * {@code long t0 = System.nanoTime()} and pass the result to
     * {@link #recordCall(String, String, long, long, long)} once the call
     * returns.  This indirection is intentional: we don't want a
     * {@code Timer.Sample} allocation in the streaming path where the hot
     * loop is the per-token emission.
     *
     * @param model the model identifier (e.g. {@code "minimaxai/minimax-m2.7"}).
     *              Must NOT be null; we coerce to {@code "unknown"} to keep
     *              the meter registry happy.
     */
    public long startTimer(String model) {
        return System.nanoTime();
    }

    /**
     * Record the end of an LLM call.  Increments both the call counter and
     * the duration timer; optionally records token counters if the caller
     * knows the sizes.
     *
     * @param model            model identifier (e.g. {@code "meta/llama-3.2-11b-vision-instruct"})
     * @param outcome          one of the {@code OUTCOME_*} constants
     * @param startNanos       the value previously returned by {@link #startTimer(String)}
     * @param promptTokens     number of input tokens; pass {@code -1} if unknown
     * @param completionTokens number of output tokens; pass {@code -1} if unknown
     */
    public void recordCall(String model, String outcome, long startNanos,
                           long promptTokens, long completionTokens) {
        long elapsedNanos = System.nanoTime() - startNanos;
        Tags tags = Tags.of("model", safe(model), "outcome", safe(outcome));
        try {
            Counter.builder(METRIC_CALLS_TOTAL)
                    .description("Total LLM invocations by model and outcome")
                    .tags(tags)
                    .register(registry)
                    .increment();
            Timer.builder(METRIC_CALL_DURATION)
                    .description("LLM call wall-clock duration by model and outcome")
                    .tags(tags)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
            if (promptTokens >= 0) {
                Counter.builder(METRIC_PROMPT_TOKENS)
                        .description("LLM prompt tokens consumed by model")
                        .tag("model", safe(model))
                        .register(registry)
                        .increment(promptTokens);
            }
            if (completionTokens >= 0) {
                Counter.builder(METRIC_COMPLETION_TOKENS)
                        .description("LLM completion tokens generated by model")
                        .tag("model", safe(model))
                        .register(registry)
                        .increment(completionTokens);
            }
        } catch (Exception e) {
            // A metrics failure must not break an LLM call — log at DEBUG
            // and continue.  The alternative (letting the exception propagate
            // into the response) would cause a 5xx on every label-set change.
            org.slf4j.LoggerFactory.getLogger(LlmCallMetrics.class)
                    .debug("[LlmCallMetrics] failed to record call: {}", e.getMessage());
        }
    }

    /**
     * Convenience: increment the call counter only (no timing).  Useful for
     * "fast-fail" paths where the call never reached the upstream — we still
     * want to count the attempt, but the wall-clock timer would be
     * misleading.
     */
    public void recordFailure(String model, String outcome) {
        Tags tags = Tags.of("model", safe(model), "outcome", safe(outcome));
        Counter.builder(METRIC_CALLS_TOTAL)
                .description("Total LLM invocations by model and outcome")
                .tags(tags)
                .register(registry)
                .increment();
    }

    private static String safe(String tag) {
        return (tag == null || tag.isEmpty()) ? "unknown" : tag;
    }
}
