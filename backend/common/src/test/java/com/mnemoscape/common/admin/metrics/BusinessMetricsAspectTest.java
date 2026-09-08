package com.mnemoscape.common.admin.metrics;

import com.mnemoscape.common.admin.metrics.BusinessMetricsAspect;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32: lightweight unit test for {@link BusinessMetricsAspect} that
 * demonstrates the meter output is correctly populated after a few
 * invocations.
 *
 * <p>This test uses {@link AspectJProxyFactory} to wrap a hand-written
 * mock controller in a Spring AOP proxy that routes through
 * {@link BusinessMetricsAspect}.  No full {@code @SpringBootTest} is
 * needed — the aspect only depends on a {@link MeterRegistry} and the
 * Spring servlet plumbing, both of which we substitute with a
 * {@link SimpleMeterRegistry} and synthetic {@code controller} / {@code uri}
 * resolution.
 *
 * <p>The ai-service module ships the full end-to-end
 * {@code /actuator/prometheus} test
 * ({@code com.mnemoscape.ai.metrics.MetricsConfigTest}); this unit test
 * is a focused, no-infra alternative that runs in any environment.
 */
@DisplayName("BusinessMetricsAspect: 3 invocations produce 3 distinct http.requests.total labels")
class BusinessMetricsAspectTest {

    private MeterRegistry registry;
    private BusinessMetricsAspect aspect;
    private MockController proxy;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aspect = new BusinessMetricsAspect(registry);

        // Wire the aspect into a Spring AOP proxy around the mock controller
        AspectJProxyFactory factory = new AspectJProxyFactory(new MockController());
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @Test
    @DisplayName("Three controller invocations register three distinct (controller,method,uri) tag sets")
    void threeInvocationsProduceThreeLabelSets() {
        // 1) Three calls — two GETs, one POST
        ResponseEntity<?> r1 = (ResponseEntity<?>) proxy.get(42);
        ResponseEntity<?> r2 = (ResponseEntity<?>) proxy.list();
        ResponseEntity<?> r3 = (ResponseEntity<?>) proxy.create(Map.of("k", "v"));

        assertThat(r1.getStatusCode().value()).isEqualTo(200);
        assertThat(r2.getStatusCode().value()).isEqualTo(200);
        assertThat(r3.getStatusCode().value()).isEqualTo(200);

        // 2) Verify the counters: 3 distinct (controller, method, uri) tag sets
        //    → 3 distinct counter instances (one per method, because the
        //    fallback uri resolution uses "ClassName#methodName" when
        //    Spring MVC dispatch hasn't populated the request attribute).
        List<Counter> counters = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("http.requests.total"))
                .map(m -> (Counter) m)
                .toList();
        assertThat(counters)
                .as("One counter per (controller, method, uri, status) tag set")
                .hasSize(3);

        // Each counter should have count = 1.0
        double totalCount = counters.stream().mapToDouble(Counter::count).sum();
        assertThat(totalCount)
                .as("Sum of all counter increments equals 3 (one per invocation)")
                .isEqualTo(3.0);

        // 3) Verify the durations: 3 distinct timer instances, one per method
        List<Timer> timers = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("http.request.duration"))
                .map(m -> (Timer) m)
                .toList();
        assertThat(timers)
                .as("One timer per (controller, method, uri, status) tag set")
                .hasSize(3);

        // 4) Spot-check a single counter: it should be tagged with the
        //    controller class name, the synthetic method=UNKNOWN (no
        //    servlet request is bound in this proxy-only test), and
        //    status=200.
        Counter c = counters.stream()
                .filter(co -> "MockController".equals(co.getId().getTag("controller")))
                .findFirst()
                .orElse(null);
        assertThat(c)
                .as("MockController counter exists")
                .isNotNull();
        assertThat(c.getId().getTag("controller")).isEqualTo("MockController");
        assertThat(c.getId().getTag("status")).isEqualTo("200");
        assertThat(c.getId().getTag("method")).isEqualTo("UNKNOWN");
        // uri tag is the synthetic "ClassName#methodName" because this
        // test bypasses Spring MVC dispatch
        assertThat(c.getId().getTag("uri")).startsWith("MockController#");

        // 5) The duration timer should have recorded at least one sample.
        long totalTimerSamples = timers.stream()
                .mapToLong(Timer::count)
                .sum();
        assertThat(totalTimerSamples)
                .as("Total timer samples equals 3")
                .isEqualTo(3L);
        long totalRecordedNanos = (long) timers.stream()
                .mapToDouble(t -> t.totalTime(TimeUnit.NANOSECONDS))
                .sum();
        assertThat(totalRecordedNanos)
                .as("Total recorded time is positive")
                .isPositive();
    }

    @RestController
    @RequestMapping("/api/mock")
    public static class MockController {
        @GetMapping("/{id}")
        public ResponseEntity<Map<String, Object>> get(@PathVariable int id) {
            return ResponseEntity.ok(Map.of("id", id));
        }

        @GetMapping
        public ResponseEntity<List<Map<String, Object>>> list() {
            return ResponseEntity.ok(List.of());
        }

        @PostMapping
        public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
            return ResponseEntity.ok(Map.of("echo", body));
        }
    }
}
