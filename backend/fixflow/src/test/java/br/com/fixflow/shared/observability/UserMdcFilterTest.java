package br.com.fixflow.shared.observability;

import br.com.fixflow.shared.infrastructure.observability.UserMdcFilter;
import br.com.fixflow.shared.infrastructure.security.AuthenticatedIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserMdcFilterTest {
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void usesAuthenticatedIdentityAndCleansUpOnException() {
        UUID userId = UUID.randomUUID();
        var principal = new AuthenticatedIdentity(userId, "user@example.com");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        assertThrows(IllegalStateException.class, () -> new UserMdcFilter().doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
                    assertEquals(userId.toString(), MDC.get("userId"));
                    throw new IllegalStateException("failure");
                }));
        assertNull(MDC.get("userId"));
    }

    @Test
    void ignoresClientIdentityHeaders() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        request.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        new UserMdcFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertNull(MDC.get("userId"));
            assertNull(MDC.get("tenantId"));
        });
    }
}
