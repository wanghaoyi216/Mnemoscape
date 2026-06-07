package com.mnemoscape.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Propagates request-scoped diagnostic context into SLF4J's MDC so every
 * downstream log line includes correlationId, userId, and username — and
 * surfaces correlationId in the response headers so clients can quote it
 * when reporting incidents.
 *
 * <p>Header contract (set by the API gateway):
 * <ul>
 *   <li>{@code X-Correlation-Id} — request trace identifier (generated here if absent)</li>
 *   <li>{@code X-User-Id} — authenticated user id (gateway-verified JWT subject)</li>
 *   <li>{@code X-User-Name} — authenticated username</li>
 * </ul>
 *
 * <p>The same values are also exposed as request attributes
 * ({@code userId}, {@code username}) so downstream controllers continue to read
 * them via {@code request.getAttribute(...)}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcContextFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_NAME_HEADER = "X-User-Name";

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_USER_NAME = "username";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        String userId = nullSafe(request.getHeader(USER_ID_HEADER));
        String username = nullSafe(request.getHeader(USER_NAME_HEADER));

        MDC.put(MDC_CORRELATION_ID, correlationId);
        if (userId != null) {
            MDC.put(MDC_USER_ID, userId);
            request.setAttribute("userId", userId);
        }
        if (username != null) {
            MDC.put(MDC_USER_NAME, username);
            request.setAttribute("username", username);
        }
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_USER_NAME);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(CORRELATION_ID_HEADER);
        if (header != null) {
            String trimmed = header.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= 128) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String nullSafe(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
