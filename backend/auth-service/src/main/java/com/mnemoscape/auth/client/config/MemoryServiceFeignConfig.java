package com.mnemoscape.auth.client.config;

import com.mnemoscape.common.exception.UpstreamUnavailableException;
import com.mnemoscape.common.exception.UpstreamUnavailableException.FailureKind;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * Per-client Feign configuration for
 * {@link com.mnemoscape.auth.client.MemoryServiceClient}.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>Cap connect + read timeouts at 5 seconds (Requirements 6.7).</li>
 *   <li>Translate every Feign-level failure into a
 *       {@link UpstreamUnavailableException} carrying a coarse-grained
 *       {@link FailureKind} so the dashboard error card can show
 *       <i>why</i> the upstream failed instead of a black 502 banner.</li>
 * </ol>
 *
 * <p><b>Authentication propagation</b> is intentionally NOT in this class —
 * it lives in the global
 * {@link com.mnemoscape.common.client.FeignAuthForwardingInterceptor} bean
 * which Spring Cloud OpenFeign automatically applies to every Feign client
 * across the platform. Adding a per-client interceptor here would be a
 * second registration that fights the global one.
 *
 * <p>This class is intentionally <b>not</b> annotated with
 * {@code @Configuration} — Spring Cloud OpenFeign treats per-client
 * configurations as non-singleton. Keeping it as a plain class isolates
 * it to the {@code memoryServiceForAdminStats} client only.
 */
public class MemoryServiceFeignConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceFeignConfig.class);

    /** Logical upstream name used for tagging the wrapped exception. */
    static final String UPSTREAM_NAME = "memory-service";

    /** 5-second cap for both connect and read phases (Requirements 6.7). */
    private static final long TIMEOUT_MS = 5_000L;

    @Bean
    public Request.Options feignRequestOptions() {
        return new Request.Options(
                TIMEOUT_MS, TimeUnit.MILLISECONDS,
                TIMEOUT_MS, TimeUnit.MILLISECONDS,
                /* followRedirects */ true);
    }

    @Bean
    public ErrorDecoder feignErrorDecoder() {
        return new MemoryServiceErrorDecoder();
    }

    /**
     * Maps every Feign error path — HTTP 4xx / 5xx, socket failures,
     * connect / read timeouts — to {@link UpstreamUnavailableException}
     * with a {@link FailureKind} that lets operators see at a glance
     * what went wrong on the dashboard.
     *
     * <p>Note: connect / read timeouts arrive as
     * {@link feign.RetryableException} <i>before</i> reaching this
     * decoder; they are handled by {@code Retryer.NEVER_RETRY} +
     * unwrapping at the call site (see
     * {@link com.mnemoscape.auth.service.AdminStatsService}). This
     * decoder only sees responses that completed an HTTP round-trip.
     */
    public static final class MemoryServiceErrorDecoder implements ErrorDecoder {
        @Override
        public Exception decode(String methodKey, Response response) {
            int status = response == null ? -1 : response.status();
            FailureKind kind;
            if (status >= 500) {
                kind = FailureKind.HTTP_5XX;
            } else if (status >= 400) {
                kind = FailureKind.HTTP_4XX;
            } else {
                kind = FailureKind.UNKNOWN;
            }
            String detail = "downstream returned HTTP " + status + " for " + methodKey;
            log.warn("memory-service feign call failed methodKey={} httpStatus={} kind={}",
                    methodKey, status, kind);
            return new UpstreamUnavailableException(UPSTREAM_NAME, kind, detail, null);
        }
    }

    @Bean
    public feign.Retryer feignRetryer() {
        return feign.Retryer.NEVER_RETRY;
    }
}
