package com.mnemoscape.ai.config;

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

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * R32: Micrometer metrics registration config for ai-service.
 *
 * <p>Wires four classes of meters into the {@link MeterRegistry} so the
 * Prometheus scrape endpoint exposes them as part of the standard
 * {@code /actuator/prometheus} surface:
 *
 * <ol>
 *   <li><b>JVM runtime</b> — memory pools, GC pauses, threads, classes,
 *       JIT compilation, CPU.  Bound via {@link JvmMemoryMetrics},
 *       {@link JvmGcMetrics}, {@link JvmThreadMetrics},
 *       {@link ClassLoaderMetrics}, {@link JvmInfoMetrics},
 *       {@link JvmCompilationMetrics}, {@link JvmHeapPressureMetrics},
 *       and {@link ProcessorMetrics}.  These are auto-configured by
 *       Spring Boot Actuator when the registry is on the classpath, but
 *       we re-declare them here so the {@code ai-service} bootstrap is
 *       self-documenting and survives an actuator auto-config refactor.</li>
 *
 *   <li><b>HikariCP</b> — when a {@code HikariDataSource} bean is on
 *       the classpath (memory-service, auth-service; ai-service has no
 *       JDBC layer today, so this binder is a graceful no-op).  We
 *       resolve the bean reflectively because the
 *       {@code com.zaxxer.hikari} package is not on ai-service's
 *       classpath; the binder looks it up by type and the lookup
 *       returns {@code null} when the type is absent.  memory-service
 *       ships a separate, type-safe equivalent
 *       ({@code com.mnemoscape.memory.config.MetricsConfig}).</li>
 *
 *   <li><b>Tomcat thread pool</b> — Spring Boot's embedded Tomcat
 *       exposes a {@code ThreadPoolExecutor} that backs the servlet
 *       connector.  We register Gauges for {@code tomcat.threads.busy},
 *       {@code tomcat.threads.config.max}, {@code tomcat.threads.current}
 *       and {@code tomcat.threads.queue.size} so dashboards can plot
 *       pool saturation alongside request rate.</li>
 *
 *   <li><b>HTTP server request time</b> — auto-configured by Spring
 *       Boot's {@code WebMvcMetricsAutoConfiguration}.  It produces
 *       {@code http.server.requests} — the canonical Tomcat / Spring
 *       MVC request meter.  Our aspect (see
 *       {@code com.mnemoscape.common.admin.metrics.BusinessMetricsAspect})
 *       emits a separate {@code http.requests.total} counter tagged
 *       on controller/method/uri — these are complementary, not
 *       duplicates.</li>
 * </ol>
 *
 * <h2>Why this lives in ai-service</h2>
 * <p>The other three services (memory, auth, gateway) either have
 * HikariCP or different connector types and ship their own
 * service-specific {@code MetricsConfig}.  ai-service is the canonical
 * reference for the JVM/GC/Tomcat wiring because it's the workload
 * that benefits most from the extra visibility (long-running LLM
 * streams, dynamic thread pools, GPU-adjacent GC pressure).
 */
@Configuration
public class MetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

    /**
     * JVM + system metrics binder.  All binders are no-op if the
     * underlying JVM feature is unavailable (e.g.
     * {@code JvmHeapPressureMetrics} silently no-ops on JDK 8; we run
     * on JDK 17 so all of these are live).
     */
    @Bean
    public JvmMetricsBinder jvmMetricsBinder(MeterRegistry registry) {
        return new JvmMetricsBinder(registry);
    }

    /**
     * Optional HikariCP binder.  Registered only when a
     * {@code HikariDataSource} is present on the classpath.  We look
     * it up by type via {@link ObjectProvider} so the bean factory
     * doesn't try to instantiate a missing class.
     */
    @Bean
    public HikariMetricsBinder hikariMetricsBinder(MeterRegistry registry,
                                                   ObjectProvider<Object> hikariDataSource) {
        return new HikariMetricsBinder(registry, hikariDataSource.getIfAvailable());
    }

    /**
     * Tomcat connector thread-pool binder.  Bound when a
     * {@link ServletWebServerApplicationContext} exposes a Tomcat web
     * server (the standard {@code spring-boot-starter-web} case).
     */
    @Bean
    public TomcatThreadPoolMetricsBinder tomcatThreadPoolMetricsBinder(
            MeterRegistry registry,
            ObjectProvider<ServletWebServerApplicationContext> serverContext) {
        return new TomcatThreadPoolMetricsBinder(registry, serverContext.getIfAvailable());
    }

    // ---------------------------------------------------------------
    // Inner binder classes.  Public so the test can inject a fresh
    // MeterRegistry and assert the resulting meters without going
    // through the full Spring context.
    // ---------------------------------------------------------------

    /**
     * Aggregates every JVM / system meter binder.  Spring Boot's
     * actuator already wires these by default; we re-declare so the
     * wiring is visible at a single class.
     */
    public static class JvmMetricsBinder {
        public JvmMetricsBinder(MeterRegistry registry) {
            new ClassLoaderMetrics().bindTo(registry);
            new JvmCompilationMetrics().bindTo(registry);
            new JvmGcMetrics().bindTo(registry);
            new JvmHeapPressureMetrics().bindTo(registry);
            new JvmInfoMetrics().bindTo(registry);
            new JvmMemoryMetrics().bindTo(registry);
            new JvmThreadMetrics().bindTo(registry);
            new ProcessorMetrics().bindTo(registry);
            log.info("[MetricsConfig] JVM / GC / Processor / ClassLoader meters bound");
        }
    }

    /**
     * Binds the standard HikariCP gauges to the registry.  The bean
     * is resolved by type via {@link ObjectProvider} so the type need
     * not be on the classpath of this module.  We use reflection to
     * read the pool name and active/idle/pending counts so we don't
     * import {@code com.zaxxer.hikari.HikariDataSource} directly
     * (ai-service has no JDBC).
     *
     * <p>For services that DO have HikariCP on the classpath, prefer
     * the typed binder in
     * {@code com.mnemoscape.memory.config.MetricsConfig}.
     */
    public static class HikariMetricsBinder {
        public HikariMetricsBinder(MeterRegistry registry, Object ds) {
            if (ds == null) {
                log.info("[MetricsConfig] no HikariDataSource bean; skipping HikariCP metrics");
                return;
            }
            try {
                // ds.getPoolName() / ds.getHikariPoolMXBean() / ds.getMaximumPoolSize() /
                // ds.getMinimumIdle()
                Object poolMx = invokeNoArg(ds, "getHikariPoolMXBean");
                String poolName = (String) invokeNoArg(ds, "getPoolName");
                if (poolName == null || poolName.isEmpty()) poolName = "HikariPool";
                Tags tags = Tags.of("pool", poolName);

                Gauge.builder("hikaricp.connections.active", poolMx, m -> (Number) invokeNoArg(m, "getActiveConnections"))
                        .description("HikariCP active connections")
                        .tags(tags)
                        .register(registry);
                Gauge.builder("hikaricp.connections.idle", poolMx, m -> (Number) invokeNoArg(m, "getIdleConnections"))
                        .description("HikariCP idle connections")
                        .tags(tags)
                        .register(registry);
                Gauge.builder("hikaricp.connections.pending", poolMx, m -> (Number) invokeNoArg(m, "getThreadsAwaitingConnection"))
                        .description("HikariCP threads awaiting connection")
                        .tags(tags)
                        .register(registry);
                Gauge.builder("hikaricp.connections", poolMx, m -> (Number) invokeNoArg(m, "getTotalConnections"))
                        .description("HikariCP total connections")
                        .tags(tags)
                        .register(registry);
                Gauge.builder("hikaricp.connections.max", ds, d -> (Number) invokeNoArg(d, "getMaximumPoolSize"))
                        .description("HikariCP maximum pool size")
                        .tags(tags)
                        .register(registry);
                Gauge.builder("hikaricp.connections.min", ds, d -> (Number) invokeNoArg(d, "getMinimumIdle"))
                        .description("HikariCP minimum idle")
                        .tags(tags)
                        .register(registry);
                log.info("[MetricsConfig] HikariCP metrics bound for pool={}", poolName);
            } catch (Exception e) {
                // If reflection fails (method renamed, different HikariCP version),
                // log and continue.  We never want metrics registration to break
                // application start-up.
                log.warn("[MetricsConfig] HikariCP metrics registration failed: {}", e.toString());
            }
        }

        private static Object invokeNoArg(Object target, String methodName) {
            try {
                Method m = target.getClass().getMethod(methodName);
                return m.invoke(target);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke " + methodName + " on " + target.getClass().getName(), e);
            }
        }
    }

    /**
     * Registers Gauges for the embedded Tomcat connector's thread pool.
     * Reads the {@link ThreadPoolExecutor} from the
     * {@code Connector.protocolHandler.executor} chain.
     */
    public static class TomcatThreadPoolMetricsBinder {
        public TomcatThreadPoolMetricsBinder(MeterRegistry registry,
                                             ServletWebServerApplicationContext ctx) {
            if (ctx == null) {
                log.info("[MetricsConfig] no ServletWebServerApplicationContext; skipping Tomcat metrics");
                return;
            }
            WebServer webServer = ctx.getWebServer();
            if (!(webServer instanceof TomcatWebServer tomcat)) {
                log.info("[MetricsConfig] web server is not Tomcat ({})", webServer.getClass().getSimpleName());
                return;
            }
            try {
                Executor executor = tomcat.getTomcat().getConnector()
                        .getProtocolHandler().getExecutor();
                if (!(executor instanceof ThreadPoolExecutor tpe)) {
                    log.info("[MetricsConfig] Tomcat executor is not a ThreadPoolExecutor ({}); skipping",
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
                Gauge.builder("tomcat.threads.queue.capacity", tpe, t -> t.getQueue().size() + t.getQueue().remainingCapacity())
                        .description("Tomcat connector thread pool queue capacity")
                        .tags(tags)
                        .register(registry);
                log.info("[MetricsConfig] Tomcat thread pool metrics bound (max={})",
                        tpe.getMaximumPoolSize());
            } catch (Exception e) {
                // Container torn down / connector not yet initialised; log
                // and move on — we never want a metrics registration to
                // break the application start-up.
                log.warn("[MetricsConfig] failed to bind Tomcat thread pool metrics: {}", e.toString());
            }
        }
    }
}
