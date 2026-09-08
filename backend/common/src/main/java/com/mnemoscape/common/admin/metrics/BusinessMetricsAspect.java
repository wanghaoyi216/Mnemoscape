package com.mnemoscape.common.admin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * R32: Business metrics AOP aspect.
 *
 * <p>Intercepts every {@link org.springframework.web.bind.annotation.RestController}
 * method and emits two meters per call:
 * <ul>
 *   <li>{@code http.requests.total} — {@link Counter} tagged by
 *       {@code controller}, {@code method} (HTTP verb), {@code uri} (URI template
 *       from the handler mapping, NEVER the raw path), and {@code status}
 *       (HTTP status code as a string).</li>
 *   <li>{@code http.request.duration} — {@link Timer} tagged by the same
 *       four labels; default histogram buckets are good enough for service
 *       mesh dashboards (p50 / p95 / p99 across handlers).</li>
 * </ul>
 *
 * <h2>Why this lives in {@code common}</h2>
 * <p>Every backend service that wants uniform HTTP observability can scan
 * {@code com.mnemoscape.common} and get this aspect wired via component scan
 * (every {@code @SpringBootApplication} in the project already scans that
 * package).  The only prerequisite is {@code spring-boot-starter-aop} on the
 * classpath — which is already pulled in by the {@code common} module.
 *
 * <h2>Cardinality discipline</h2>
 * <p>The {@code uri} tag is the Spring handler mapping template
 * (e.g. {@code /api/v1/users/{userId}}), not the raw request URI
 * (e.g. {@code /api/v1/users/42}).  A raw path would explode label
 * cardinality to one per request ID and break Prometheus storage.  We resolve
 * the template through {@link org.springframework.web.servlet.HandlerMapping}
 * where the {@code HandlerMappingIntrospector} has already populated the
 * best-matching pattern attribute on the request.
 *
 * <h2>Status resolution</h2>
 * <p>We try four sources in order of accuracy:
 * <ol>
 *   <li>The {@code ResponseEntity} returned by the controller (sync path).</li>
 *   <li>The exception thrown — classified via
 *       {@link #classifyException(Throwable)} so a thrown
 *       {@code BizException(code=404)} becomes {@code 404}.</li>
 *   <li>The current servlet response status (set by a filter or error
 *       controller).</li>
 *   <li>Fallback {@code 500} (only if everything else is null — should not
 *       happen in practice).</li>
 * </ol>
 */
@Aspect
@Component
public class BusinessMetricsAspect {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetricsAspect.class);

    public static final String METRIC_REQUESTS_TOTAL = "http.requests.total";
    public static final String METRIC_REQUEST_DURATION = "http.request.duration";

    private final MeterRegistry registry;

    public BusinessMetricsAspect(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");
    }

    /**
     * Pointcut: any public method whose declaring class is annotated with
     * {@code @RestController}.  We deliberately skip {@code @Controller}
     * (non-REST) so we don't double-count SSR endpoints.
     *
     * <p>{@code @within(...)} is used rather than the more compact
     * {@code execution(public * @RestController+.*(..))} form because the
     * latter has surprising semantics when the annotation is on a
     * non-public class (an inner / static-nested class) — the
     * {@code +} suffix would fail to match.  The {@code @within} form
     * works on the class reference and is unambiguous.
     */
    @Around("execution(public * *(..)) && @within(org.springframework.web.bind.annotation.RestController)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long startNanos = System.nanoTime();
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = sra == null ? null : sra.getRequest();
        String httpMethod = request == null ? "UNKNOWN" : request.getMethod();
        String uri = resolveUriTemplate(request, pjp);
        String controller = pjp.getTarget() == null
                ? "unknown"
                : pjp.getTarget().getClass().getSimpleName();

        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            String status = resolveStatus(result, error, sra);
            recordMetrics(controller, httpMethod, uri, status, elapsedNanos);
        }
    }

    /**
     * Look up the URI template produced by Spring's
     * {@code RequestMappingHandlerMapping}.  Falls back to
     * {@code "UNKNOWN"} when the attribute is not present (e.g. async dispatch
     * race, or a non-MVC request) — never to the raw path, which would blow up
     * label cardinality.
     */
    private static String resolveUriTemplate(HttpServletRequest request, ProceedingJoinPoint pjp) {
        if (request == null) {
            return deriveFromMethod(pjp);
        }
        Object pattern = request.getAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
        if (pattern instanceof String s && !s.isEmpty()) {
            return s;
        }
        return deriveFromMethod(pjp);
    }

    /**
     * Last-ditch fallback for when no request attribute is set (unit tests
     * that bypass Spring MVC dispatch).  We synthesize
     * {@code ClassName#methodName} so the metric is still bucketed and not
     * dropped silently.  The cardinality is bounded by the number of
     * controller methods in the codebase.
     */
    private static String deriveFromMethod(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Class<?> declaring = sig.getDeclaringType();
        String simpleName = declaring == null ? "unknown" : declaring.getSimpleName();
        return simpleName + "#" + sig.getName();
    }

    /**
     * Determine the HTTP status code as a string.  Order of preference:
     * controller return value, thrown exception, servlet response,
     * fallback.  We use a string tag (not a number) so the cardinality is
     * bounded by the small set of HTTP status codes the app emits.
     */
    private static String resolveStatus(Object result, Throwable error,
                                        ServletRequestAttributes sra) {
        if (result instanceof ResponseEntity<?> re) {
            return String.valueOf(re.getStatusCode().value());
        }
        if (error != null) {
            return classifyException(error);
        }
        if (sra != null && sra.getResponse() != null) {
            int sc = sra.getResponse().getStatus();
            if (sc != 0) return String.valueOf(sc);
        }
        return String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    /**
     * Map known exception types to HTTP statuses.  Kept deliberately small —
     * we only handle the high-traffic, observability-relevant families
     * (BizException carries its own code; Spring's ResponseStatusException
     * carries one too).  Anything we don't recognise becomes 500, which
     * Prometheus alerts on anyway.
     */
    private static String classifyException(Throwable t) {
        if (t instanceof org.springframework.web.server.ResponseStatusException rse) {
            return String.valueOf(rse.getStatusCode().value());
        }
        if (t.getClass().getName().endsWith("BizException")) {
            // Reflection-light: most modules in this project put a getCode()
            // method on their BizException.  We swallow any failure to invoke
            // it and fall back to 500 — the alert still fires.
            try {
                Object code = t.getClass().getMethod("getCode").invoke(t);
                if (code instanceof Integer i) return String.valueOf(i);
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        return String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    private void recordMetrics(String controller, String method, String uri,
                               String status, long elapsedNanos) {
        try {
            Tags tags = Tags.of(
                    "controller", controller,
                    "method", method,
                    "uri", uri,
                    "status", status
            );
            Counter.builder(METRIC_REQUESTS_TOTAL)
                    .description("Total HTTP requests handled by @RestController methods")
                    .tags(tags)
                    .register(registry)
                    .increment();
            Timer.builder(METRIC_REQUEST_DURATION)
                    .description("Wall-clock duration of @RestController method invocation")
                    .tags(tags)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            // Never let a metrics failure cascade into a 5xx — the aspect is
            // a cross-cutting concern and must not poison the request path.
            log.debug("[BusinessMetricsAspect] failed to record metric: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Accessors for tests.  Not part of the runtime contract.
    // ---------------------------------------------------------------

    static String safe(String tag) {
        return (tag == null || tag.isEmpty()) ? "unknown" : tag;
    }
}
