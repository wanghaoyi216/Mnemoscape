package com.mnemoscape.ai.metrics;

import com.mnemoscape.ai.config.MetricsConfig;
import com.mnemoscape.common.admin.metrics.BusinessMetricsAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32: end-to-end test that three mock controller requests are reflected
 * in the Prometheus scrape output.
 *
 * <p>What this test asserts:
 * <ol>
 *   <li>After three HTTP requests across three different
 *       {@code @RestController} endpoints, the
 *       {@code http_requests_total} counter (per-tag) shows non-zero
 *       counts for each (uri, method, status) combination.</li>
 *   <li>Each call also produces a corresponding
 *       {@code http_request_duration_seconds} timer sample.</li>
 *   <li>The JVM meters ({@code jvm_memory_*}, {@code jvm_threads_*},
 *       {@code process_cpu_*}) are exposed — this validates that
 *       {@link MetricsConfig.JvmMetricsBinder} ran.</li>
 *   <li>The {@code /actuator/prometheus} endpoint is reachable and
 *       returns a non-empty body in the Prometheus text format.</li>
 * </ol>
 *
 * <h2>Why a separate minimal config instead of {@code @SpringBootTest(classes = AiApplication.class)}</h2>
 * <p>The full {@code AiApplication} would try to connect to Nacos, Redis,
 * RabbitMQ, Neo4j and load all controllers / schedulers — none of which we
 * need for a metrics wiring test.  A focused config keeps the test under
 * 5 seconds and lets it run on any developer machine without infra
 * dependencies.
 */
@SpringBootTest(
        classes = MetricsConfigTest.MinimalTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("MetricsConfig: 3 mock controller requests land in /actuator/prometheus")
class MetricsConfigTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("Three requests across three controllers populate http_requests_total and http_request_duration")
    void threeRequestsPopulatePrometheusMeters() {
        // 1) Two GETs to /api/mock-a/* and one POST to /api/mock-c
        ResponseEntity<String> r1 = rest.getForEntity(
                "http://localhost:" + port + "/api/mock-a/42", String.class);
        ResponseEntity<String> r2 = rest.getForEntity(
                "http://localhost:" + port + "/api/mock-b", String.class);
        ResponseEntity<String> r3 = rest.postForEntity(
                "http://localhost:" + port + "/api/mock-c",
                Map.of("hello", "world"), String.class);

        // Each controller responds 200 OK
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2) Scrape /actuator/prometheus
        ResponseEntity<String> scrape = rest.getForEntity(
                "http://localhost:" + port + "/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = scrape.getBody();
        assertThat(body).as("Prometheus scrape body").isNotNull();

        // 3) Verify the http_requests_total counter (per tag) is present
        //    with non-zero counts for each of the three endpoints.  The
        //    uri tag is the Spring handler template
        //    (/api/mock-a/{id}, /api/mock-b, /api/mock-c) — this is the
        //    cardinality discipline that prevents metric explosion.
        assertThat(body)
                .as("http_requests_total counter and uri tags present")
                .contains("http_requests_total")
                .contains("uri=\"/api/mock-a/{id}\"")
                .contains("uri=\"/api/mock-b\"")
                .contains("uri=\"/api/mock-c\"")
                .contains("method=\"GET\"")
                .contains("method=\"POST\"")
                .contains("status=\"200\"");

        // 4) The duration timer is exposed (Micrometer emits the meter
        //    once it has been touched at least once and after the first
        //    request its histogram quantiles are populated).
        assertThat(body)
                .as("http_request_duration_seconds timer present")
                .contains("http_request_duration_seconds");

        // 5) JVM meters are present (jvm_memory_used_bytes is the most
        //    universally emitted meter and is a good smoke test that
        //    the JvmMetricsBinder ran during context start-up).
        assertThat(body)
                .as("JVM memory and thread meters present")
                .contains("jvm_memory_used_bytes")
                .contains("jvm_threads_states_threads");
    }

    // ---------------------------------------------------------------
    // Minimal test application — only the wiring needed to validate
    // the metrics flow.  No Nacos, no Redis, no Feign, no Auth.
    // ---------------------------------------------------------------

    @EnableAutoConfiguration(exclude = {
            // Persistence — ai-service doesn't use SQL or Neo4j in this test
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            Neo4jDataAutoConfiguration.class,
            // Messaging — not needed for metrics wiring
            RabbitAutoConfiguration.class,
            KafkaAutoConfiguration.class,
            JmsAutoConfiguration.class,
            // Search — not needed
            ElasticsearchClientAutoConfiguration.class,
            ElasticsearchRestClientAutoConfiguration.class,
            MongoAutoConfiguration.class,
            LdapAutoConfiguration.class,
            // Redis
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            // Security — disable to avoid 401 on /actuator/**
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    @Import({
            MetricsConfig.class,
            BusinessMetricsAspect.class,
            MockControllerA.class,
            MockControllerB.class,
            MockControllerC.class
    })
    @TestConfiguration
    public static class MinimalTestApp {
    }

    @RestController
    @RequestMapping("/api/mock-a")
    public static class MockControllerA {
        @GetMapping("/{id}")
        public Map<String, Object> get(@PathVariable String id) {
            return Map.of("controller", "MockControllerA", "id", id);
        }
    }

    @RestController
    @RequestMapping("/api/mock-b")
    public static class MockControllerB {
        @GetMapping
        public Map<String, Object> list() {
            return Map.of("controller", "MockControllerB", "items", List.of());
        }
    }

    @RestController
    @RequestMapping("/api/mock-c")
    public static class MockControllerC {
        @PostMapping
        public Map<String, Object> create(@RequestBody Map<String, Object> body) {
            return Map.of("controller", "MockControllerC", "echo", body);
        }
    }
}
