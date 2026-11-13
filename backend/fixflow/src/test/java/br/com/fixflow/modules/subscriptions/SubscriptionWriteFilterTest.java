package br.com.fixflow.modules.subscriptions;

import br.com.fixflow.modules.subscriptions.application.Subscriptions;
import br.com.fixflow.modules.subscriptions.domain.SubscriptionAccess;
import br.com.fixflow.modules.subscriptions.infrastructure.SubscriptionWriteFilter;
import br.com.fixflow.shared.infrastructure.security.AuthenticatedIdentity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionWriteFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private int request(String method, String path, String status, boolean writable) throws Exception {
        var subscriptions = mock(Subscriptions.class);
        when(subscriptions.access()).thenReturn(new SubscriptionAccess(status, writable, Instant.now(), 0));
        var identity = new AuthenticatedIdentity(UUID.randomUUID(), "owner@example.com", UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, java.util.List.of()));
        var response = new MockHttpServletResponse();
        new SubscriptionWriteFilter(subscriptions).doFilter(new MockHttpServletRequest(method, path), response,
            (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        return response.getStatus();
    }
    @Test void firstPaymentBlocksBusinessReadsAndWrites() throws Exception {
        assertEquals(402, request("GET", "/api/v1/work-orders", "PAYMENT_REQUIRED", false));
        assertEquals(402, request("POST", "/api/v1/work-orders", "PAYMENT_REQUIRED", false));
        assertEquals(204, request("POST", "/api/v1/subscription", "PAYMENT_REQUIRED", false));
        assertEquals(204, request("GET", "/api/v1/subscription/access", "PAYMENT_REQUIRED", false));
    }
    @Test void confirmedPaymentAndStarterTrialAllowBusinessAccess() throws Exception {
        assertEquals(204, request("GET", "/api/v1/work-orders", "ACTIVE", true));
        assertEquals(204, request("POST", "/api/v1/work-orders", "TRIALING", true));
    }
    @Test void expiredExistingAccountsKeepReadAccess() throws Exception {
        assertEquals(204, request("GET", "/api/v1/work-orders", "PAST_DUE", false));
        assertEquals(402, request("POST", "/api/v1/work-orders", "PAST_DUE", false));
    }
}
