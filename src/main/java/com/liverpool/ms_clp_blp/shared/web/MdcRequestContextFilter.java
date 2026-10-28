package com.liverpool.ms_clp_blp.shared.web;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the MDC (Mapped Diagnostic Context) for every inbound request so that all log lines
 * emitted during request processing carry consistent correlation fields (Rule 11).
 *
 * <p>Fields set per request:
 * <ul>
 *   <li>{@code requestId} — echoed from {@code x-request-id} or generated as a UUID.</li>
 *   <li>{@code correlationId} — propagated from {@code x-correlation-id} or falls back to
 *       {@code requestId} so a single upstream trace can span multiple downstream hops.</li>
 *   <li>{@code operation} — HTTP method + raw request URI (e.g. {@code "GET /blp/es-mx/tienda/esteelauder/catst26617625"}).</li>
 * </ul>
 *
 * <p>The service identity is intentionally <em>not</em> placed in the MDC: the ECS structured log
 * format ({@code logging.structured.format.console=ecs}) already emits a reserved {@code service}
 * object (populated from {@code spring.application.name}). Adding an MDC key named {@code service}
 * collides with it and makes the encoder throw {@code "The name 'service' has already been
 * written"}, dropping the log event.</p>
 *
 * <p>{@code traceId} and {@code spanId} are managed automatically by Micrometer Tracing + Brave via
 * {@code MDCScopeDecorator} and do not require manual population here.</p>
 *
 * <p>All three fields are unconditionally removed in a {@code finally} block so they never leak
 * across requests when the underlying thread (or virtual thread) is reused.</p>
 *
 * <p>The filter runs at {@link Ordered#HIGHEST_PRECEDENCE} + 10, before the Spring Security filter
 * chain, so that security-related events are also tagged with the correlation fields.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcRequestContextFilter extends OncePerRequestFilter {

    static final String HEADER_REQUEST_ID = "x-request-id";
    static final String HEADER_CORRELATION_ID = "x-correlation-id";

    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_OPERATION = "operation";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolveOrGenerate(request.getHeader(HEADER_REQUEST_ID));
        String correlationId = resolveOrFallback(request.getHeader(HEADER_CORRELATION_ID), requestId);
        String operation = request.getMethod() + " " + request.getRequestURI();

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_OPERATION, operation);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_OPERATION);
        }
    }

    private static String resolveOrGenerate(String header) {
        return (header != null && !header.isBlank()) ? header : UUID.randomUUID().toString();
    }

    private static String resolveOrFallback(String header, String fallback) {
        return (header != null && !header.isBlank()) ? header : fallback;
    }
}
