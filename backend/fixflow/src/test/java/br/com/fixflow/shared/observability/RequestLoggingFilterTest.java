package br.com.fixflow.shared.observability;

import br.com.fixflow.shared.infrastructure.observability.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RequestLoggingFilterTest {
    @Test
    void propagatesIdentifiersAndCleansUpOnFailure() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-123");
        request.addHeader("X-Correlation-Id", "flow-456");
        var response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> new RequestLoggingFilter().doFilter(request, response,
                (req, res) -> {
                    assertEquals("request-123", MDC.get("requestId"));
                    assertEquals("flow-456", MDC.get("correlationId"));
                    throw new IllegalStateException("failure");
                }));
        assertEquals("request-123", response.getHeader("X-Request-Id"));
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void replacesInvalidIdentifiersAndDoesNotReusePreviousRequest() throws Exception {
        for (int i = 0; i < 2; i++) {
            var request = new MockHttpServletRequest();
            request.addHeader("X-Request-Id", "invalid id");
            var response = new MockHttpServletResponse();
            new RequestLoggingFilter().doFilter(request, response, (req, res) -> {
                assertDoesNotThrow(() -> java.util.UUID.fromString(MDC.get("requestId")));
                assertEquals(MDC.get("requestId"), MDC.get("correlationId"));
            });
            assertNull(MDC.get("requestId"));
        }
    }
}
