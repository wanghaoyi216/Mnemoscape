package com.mnemoscape.memory.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmCompilationMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmInfoMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * R32: type-safe Micrometer metrics registration for memory-service.
 *
 * <p>Memory-service has both a HikariCP JDBC connection pool and an
 * embedded Tomcat, so this config is the place where the type-safe
 * HikariCP binding lives (the ai-service version uses reflection
 * because ai-service has no JDBC layer).  See
 * {@code com.mnemoscape.ai.config.MetricsConfig} for the reference
 * JVM / Tomcat wiring.
 *
 * <p>Meters registered:
 * <ul>
 *   <li>JVM runtime — {@code jvm.memory.*}, {@code jvm.gc.*},
 *       {@code jvm.threads.*}, {@code jvm.classes.*},
 *       {@code jvm.compilation.*}, {@code jvm.heap.pressure.*},
 *       {@code process.cpu.*}.</li>
 *   <li>HikariCP — {@code hikaricp.connections.active},
 *       {@code hikaricp.connections.idle},
 *       {@code hikaricp.connections.pending},
 *       {@code hikaricp.connections},
 *       {@code hikaricp.connections.max},
 *       {@code hikaricp.connections.min}.</li>
 *   <li>Tomcat — {@code tomcat.threads.busy},
 *       {@code tomcat.threads.current},
 *       {@code tomcat.threads.config.max},
 *       {@code tomcat.threads.queue.size}.</li>
 * </ul>
 *
 * <p>All binders are conditional — if the underlying bean is absent
 * (e.g. memory-service runs in a test profile that disables the data
 * source), the binder logs a one-liner and registers nothing.
 */
@Configuration
public class MemoryMetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryMetricsConfig.class);

    @Bean
    public MemoryJvmMetricsBinder memoryJvmMetricsBinder(MeterRegistry registry) {
        return new MemoryJvmMetricsBinder(registry);
    }

    @Bean
    public MemoryHikariMetricsBinder memoryHikariMetricsBinder(
            MeterRegistry registry,
            ObjectProvider<HikariDataSource> hikariDataSource) {
        return new MemoryHikariMetricsBinder(registry, hikariDataSource.getIfAvailable());
    }

    @Bean
    public MemoryTomcatThreadPoolMetricsBinder memoryTomcatThreadPoolMetricsBinder(
            MeterRegistry registry,
            ObjectProvider<ServletWebServerApplicationContext> serverContext) {
        return new MemoryTomcatThreadPoolMetricsBinder(registry, serverContext.getIfAvailable());
    }

    // ---------------------------------------------------------------
    // Binders
    // ---------------------------------------------------------------

    public static class MemoryJvmMetricsBinder {
        public MemoryJvmMetricsBinder(MeterRegistry registry) {
            new ClassLoaderMetrics().bindTo(registry);
            new JvmCompilationMetrics().bindTo(registry);
            new JvmGcMetrics().bindTo(registry);
            new JvmHeapPressureMetrics().bindTo(registry);
            new JvmInfoMetrics().bindTo(registry);
            new JvmMemoryMetrics().bindTo(registry);
            new JvmThreadMetrics().bindTo(registry);
            new ProcessorMetrics().bindTo(registry);
            log.info("[MemoryMetricsConfig] JVM / GC / Processor / ClassLoader meters bound");
        }
    }

    public static class MemoryHikariMetricsBinder {
        public MemoryHikariMetricsBinder(MeterRegistry registry, HikariDataSource ds) {
            if (ds == null) {
                log.info("[MemoryMetricsConfig] no HikariDataSource bean; skipping HikariCP metrics");
                return;
            }
            String poolName = ds.getPoolName() == null ? "HikariPool" : ds.getPoolName();
            Tags tags = Tags.of("pool", poolName);
            HikariPoolMXBean mx = ds.getHikariPoolMXBean();
            Gauge.builder("hikaricp.connections.active", mx, HikariPoolMXBean::getActiveConnections)
                    .description("HikariCP active connections")
                    .tags(tags)
                    .register(registry);
            Gauge.builder("hikaricp.connections.idle", mx, HikariPoolMXBean::getIdleConnections)
                    .description("HikariCP idle connections")
                    .tags(tags)
                    .register(registry);
            Gauge.builder("hikaricp.connections.pending", mx, HikariPoolMXBean::getThreadsAwaitingConnection)
                    .description("HikariCP threads awaiting connection")
                    .tags(tags)
                    .register(registry);
            Gauge.builder("hikaricp.connections", mx, HikariPoolMXBean::getTotalConnections)
                    .description("HikariCP total connections")
                    .tags(tags)
                    .register(registry);
            Gauge.builder("hikaricp.connections.max", ds, HikariDataSource::getMaximumPoolSize)
                    .description("HikariCP maximum pool size")
                    .tags(tags)
                    .register(registry);
            Gauge.builder("hikaricp.connections.min", ds, HikariDataSource::getMinimumIdle)
                    .description("HikariCP minimum idle")
                    .tags(tags)
                    .register(registry);
            log.info("[MemoryMetricsConfig] HikariCP metrics bound for pool={}", poolName);
        }
    }

    public static class MemoryTomcatThreadPoolMetricsBinder {
        public MemoryTomcatThreadPoolMetricsBinder(MeterRegistry registry,
                                                  ServletWebServerApplicationContext ctx) {
            if (ctx == null) {
                log.info("[MemoryMetricsConfig] no ServletWebServerApplicationContext; skipping Tomcat metrics");
                return;
            }
            WebServer webServer = ctx.getWebServer();
            if (!(webServer instanceof TomcatWebServer tomcat)) {
                log.info("[MemoryMetricsConfig] web server is not Tomcat ({})",
                        webServer.getClass().getSimpleName());
                return;
            }
            try {
                Executor executor = tomcat.getTomcat().getConnector()
                        .getProtocolHandler().getExecutor();
                if (!(executor instanceof ThreadPoolExecutor tpe)) {
                    log.info("[MemoryMetricsConfig] Tomcat executor is not a ThreadPoolExecutor ({}); skipping",
                            executor.getClass().getSimpleName());
                    return;
                }
                Tags tags = Tags.of("executor", "tomcat-http-nio");
                Gauge.builder("tomcat.threads.busy", tpe, ThreadPoolExecutor::getActiveCount)
                        .description("Tomcat connector threads currently executing a task")
                        .tags(tags)
                        .register(registry);
                Gauge.builder("tomcat.threads.current", tpe, ThreadPoolExecutor::getPoolSize)
                        .description("Tomcat connector current thread pool size")
                        .tags(tags)
                        .register(registry);
                Gauge.builder("tomcat.threads.config.max", tpe, ThreadPoolExecutor::getMaximumPoolSize)
                        .description("Tomcat connector maximum thread pool size")
                        .tags(tags)
                        .register(registry);
                Gauge.builder("tomcat.threads.queue.size", tpe, t -> t.getQueue().size())
                        .description("Tomcat connector thread pool queue depth")
                        .tags(tags)
                        .register(registry);
                log.info("[MemoryMetricsConfig] Tomcat thread pool metrics bound (max={})",
                        tpe.getMaximumPoolSize());
            } catch (Exception e) {
                log.warn("[MemoryMetricsConfig] failed to bind Tomcat thread pool metrics: {}", e.toString());
            }
        }
    }
}
