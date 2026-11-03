package br.com.fixflow.shared.infrastructure.observability;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        MDC.remove("userId");
        String requestId = identifier(request.getHeader("X-Request-Id"));
        String correlation = request.getHeader("X-Correlation-Id");
        String correlationId = correlation == null ? requestId : identifier(correlation);
        MDC.put("requestId", requestId);
        MDC.put("correlationId", correlationId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Correlation-Id", correlationId);
        long start = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            failed = true;
            throw exception;
        } finally {
            try {
                log.atInfo().addKeyValue("method", request.getMethod())
                        .addKeyValue("status", failed ? 500 : response.getStatus())
                        .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                        .log("HTTP request completed");
            } finally {
                MDC.remove("requestId");
                MDC.remove("correlationId");
                MDC.remove("userId");
            }
        }
    }

    private static String identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}")
                ? value : UUID.randomUUID().toString();
    }
}
