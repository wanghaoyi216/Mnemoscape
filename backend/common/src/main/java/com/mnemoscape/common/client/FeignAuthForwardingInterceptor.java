package com.mnemoscape.common.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Shared {@link RequestInterceptor} that propagates the caller's authentication
 * context to downstream services across <b>every</b> Feign client in the
 * platform.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Each microservice's {@code SecurityFilterChain} (especially the admin one
 * gated by {@code hasRole('ADMIN')}) requires a valid {@code Authorization:
 * Bearer ...} header. Without explicit propagation, Feign-issued requests go
 * out anonymously and the downstream filter rejects them with HTTP 401 — which
 * the {@code ErrorDecoder} layer then masks as
 * {@code UPSTREAM_UNAVAILABLE}, leaving the dashboard with a confusing 502
 * banner whose true cause was "you forgot to forward the token".
 *
 * <p>Before this class was introduced, only {@code MemoryServiceClient} on
 * auth-service had its own ad-hoc interceptor; the other five Feign clients
 * (memory→auth for username lookups, ai→memory for RAG, ai→asset for media,
 * resonance→memory for the public pool, etc.) all silently went out without
 * credentials. Centralising the propagation logic ensures any new Feign
 * client added to any service inherits the same behaviour.
 *
 * <h2>What gets propagated</h2>
 *
 * <ul>
 *   <li>{@code Authorization} — the bearer token, primary credential.</li>
 *   <li>{@code X-User-Id} — gateway-injected user ID (R2.4 of admin-dashboard).</li>
 *   <li>{@code X-User-Role} — gateway-injected role (R2.4).</li>
 *   <li>{@code X-User-Name} — gateway-injected username for log enrichment.</li>
 *   <li>{@code X-Correlation-Id} — request ID so audit lines on both sides
 *       share the same identifier.</li>
 *   <li>{@code Accept-Language} — i18n consistency end-to-end.</li>
 * </ul>
 *
 * <h2>Wiring</h2>
 *
 * <p>Each service registers this as a regular {@code @Bean RequestInterceptor}
 * in its main configuration. Spring Cloud OpenFeign auto-detects every
 * {@code RequestInterceptor} bean in the parent context and applies it to
 * every Feign client unless a per-client {@code configuration} class
 * <i>replaces</i> the interceptor list. We deliberately rely on bean-level
 * registration (not per-client configuration) so the interceptor is global
 * by default — matching the principle that "auth propagation is not opt-in".
 *
 * <h2>Threading model</h2>
 *
 * <p>The interceptor reads from Spring MVC's per-request
 * {@link RequestContextHolder} thread-local. Synchronous request paths work
 * out of the box. Asynchronous paths ({@code @Async}, scheduled tasks,
 * reactive bridges) lose the thread-local; in those cases the caller MUST
 * either (a) capture the {@code Authorization} header before dispatching to
 * the async pool, or (b) use a service account token. The admin-dashboard
 * stats endpoints are synchronous so this is not a concern there.
 */
@Component
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class FeignAuthForwardingInterceptor implements RequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FeignAuthForwardingInterceptor.class);

    /** Headers that should always travel with the request, in the order
     *  documented in the class javadoc. */
    private static final String[] FORWARDED_HEADERS = {
            "Authorization",
            "X-User-Id",
            "X-User-Role",
            "X-User-Name",
            "X-Correlation-Id",
            "Accept-Language",
    };

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            // Not in a request context — most likely an @Async path or a
            // scheduled task. Cannot recover the caller's token from here;
            // the call goes out anonymously which is the historical behaviour.
            // We log at TRACE so background-job traffic doesn't drown the logs.
            if (log.isTraceEnabled()) {
                log.trace("Feign call outside request context; auth headers not propagated");
            }
            return;
        }
        HttpServletRequest req = attrs.getRequest();
        for (String name : FORWARDED_HEADERS) {
            String value = req.getHeader(name);
            if (value == null || value.isBlank()) {
                continue;
            }
            // Idempotent set: remove before adding so retries / multiple
            // interceptor invocations don't accumulate duplicate values.
            template.removeHeader(name);
            template.header(name, value);
        }
    }
}
