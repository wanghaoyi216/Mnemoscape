package com.mnemoscape.memory.config;

import com.mnemoscape.common.web.MdcContextFilter;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure unit test — no Spring context. Builds the bean directly and pokes a
 * {@link RequestTemplate} through it under various MDC scenarios so we can
 * trust that an inbound trace id will, in fact, ride along on Feign-bound
 * RPCs from memory-service to ai-service.
 */
class FeignCorrelationConfigTest {

    private final FeignCorrelationConfig config = new FeignCorrelationConfig();

    @AfterEach
    void clearMdc() {
        // Tests share a thread under JUnit's default execution mode — leaving
        // MDC keys behind would leak into the next test and produce false greens.
        MDC.clear();
    }

    @Test
    @DisplayName("populated MDC → all three trace headers ride along")
    void copiesAllHeadersWhenMdcPopulated() {
        MDC.put(MdcContextFilter.MDC_CORRELATION_ID, "trace-abc-123");
        MDC.put(MdcContextFilter.MDC_USER_ID, "user-42");
        MDC.put(MdcContextFilter.MDC_USER_NAME, "alice");

        RequestTemplate template = new RequestTemplate();
        getInterceptor().apply(template);

        assertEquals("trace-abc-123", firstHeader(template, MdcContextFilter.CORRELATION_ID_HEADER));
        assertEquals("user-42",       firstHeader(template, MdcContextFilter.USER_ID_HEADER));
        assertEquals("alice",         firstHeader(template, MdcContextFilter.USER_NAME_HEADER));
    }

    @Test
    @DisplayName("empty MDC → no headers injected (avoids forging a fake trace id)")
    void skipsWhenMdcEmpty() {
        RequestTemplate template = new RequestTemplate();
        getInterceptor().apply(template);

        assertFalse(template.headers().containsKey(MdcContextFilter.CORRELATION_ID_HEADER),
                "should not set correlation header when MDC empty");
        assertFalse(template.headers().containsKey(MdcContextFilter.USER_ID_HEADER));
        assertFalse(template.headers().containsKey(MdcContextFilter.USER_NAME_HEADER));
    }

    @Test
    @DisplayName("blank MDC values are treated as absent")
    void skipsBlankMdcValues() {
        MDC.put(MdcContextFilter.MDC_CORRELATION_ID, "   ");
        MDC.put(MdcContextFilter.MDC_USER_ID, "");

        RequestTemplate template = new RequestTemplate();
        getInterceptor().apply(template);

        assertFalse(template.headers().containsKey(MdcContextFilter.CORRELATION_ID_HEADER));
        assertFalse(template.headers().containsKey(MdcContextFilter.USER_ID_HEADER));
    }

    @Test
    @DisplayName("existing header on RequestTemplate wins — no duplicate appended")
    void doesNotOverwriteExistingHeader() {
        // Some downstream code may pre-stamp a Correlation-Id (e.g. a manually
        // crafted out-of-band call). We must not append a second value, since
        // MdcContextFilter#getHeader returns just the first and a stale MDC
        // entry would silently shadow the intentional one.
        MDC.put(MdcContextFilter.MDC_CORRELATION_ID, "from-mdc");

        RequestTemplate template = new RequestTemplate();
        template.header(MdcContextFilter.CORRELATION_ID_HEADER, "preset-by-caller");
        getInterceptor().apply(template);

        Collection<String> values = template.headers().get(MdcContextFilter.CORRELATION_ID_HEADER);
        assertNotNull(values);
        assertEquals(1, values.size(), "interceptor must not append a duplicate header");
        assertTrue(values.contains("preset-by-caller"));
        assertFalse(values.contains("from-mdc"));
    }

    @Test
    @DisplayName("partial MDC — only present keys propagate")
    void propagatesOnlyPresentKeys() {
        MDC.put(MdcContextFilter.MDC_CORRELATION_ID, "trace-xyz");
        // userId / username deliberately not set — e.g. anonymous endpoint

        RequestTemplate template = new RequestTemplate();
        getInterceptor().apply(template);

        assertEquals("trace-xyz", firstHeader(template, MdcContextFilter.CORRELATION_ID_HEADER));
        assertFalse(template.headers().containsKey(MdcContextFilter.USER_ID_HEADER));
        assertFalse(template.headers().containsKey(MdcContextFilter.USER_NAME_HEADER));
    }

    // — helpers —

    private RequestInterceptor getInterceptor() {
        return config.correlationPropagatingInterceptor();
    }

    private static String firstHeader(RequestTemplate template, String name) {
        Collection<String> values = template.headers().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.iterator().next();
    }
}
