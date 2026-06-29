package com.momstarter.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Per-request access log filter.
 *
 * <p>Sets MDC fields before each request so every log line produced during that request
 * automatically carries: requestId, method, path, status, durationMs.
 * These fields are included by logback-spring.xml (LogstashEncoder) in JSON output,
 * which Filebeat ships to Elasticsearch for Kibana dashboards.
 *
 * <p>SECURITY: this filter intentionally does NOT log request bodies, response bodies,
 * Authorization headers, or any token/credential values to prevent sensitive/health
 * data and PDPA-covered PII from appearing in logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long startNanos = System.nanoTime();

        // Generate a per-request correlation ID (short UUID for readability)
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Sanitised path — strip context-path prefix; never log query string (may contain tokens)
        String path = request.getRequestURI();

        MDC.put("requestId", requestId);
        MDC.put("method", request.getMethod());
        MDC.put("path", path);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();

            MDC.put("status", String.valueOf(status));
            MDC.put("durationMs", String.valueOf(durationMs));

            // Single access-log line per request at INFO.
            // Fields flow automatically into the JSON envelope via MDC.
            log.info("access");

            // Always clear MDC to avoid leaking values across thread-pool reuse
            MDC.clear();
        }
    }
}
