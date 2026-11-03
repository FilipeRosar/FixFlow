package br.com.fixflow.shared.tenant;

import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import br.com.fixflow.shared.infrastructure.tenant.TenantContextCleanupFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextCleanupFilterTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final TenantContextCleanupFilter filter =
            new TenantContextCleanupFilter();

    @AfterEach
    void cleanUp() {
        TenantContext.clearTenantId();
    }

    @Test
    void shouldClearStaleContextBeforeRequestAndAfterward()
            throws Exception {

        TenantContext.setTenantId(TENANT_A);

        Filter downstreamFilter = (request, response, chain) -> {
            assertTrue(TenantContext.getTenantId().isEmpty());
            TenantContext.setTenantId(TENANT_A);
        };

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new MockFilterChain(new HttpServlet() {}, downstreamFilter)
        );

        assertTrue(TenantContext.getTenantId().isEmpty());
    }

    @Test
    void shouldClearContextWhenDownstreamFilterThrows()
            throws Exception {

        Filter downstreamFilter = (request, response, chain) -> {
            TenantContext.setTenantId(TENANT_A);
            throw new IllegalStateException("Erro simulado");
        };

        assertThrows(
                IllegalStateException.class,
                () -> filter.doFilter(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        new MockFilterChain(new HttpServlet() {}, downstreamFilter)
                )
        );

        assertTrue(TenantContext.getTenantId().isEmpty());
    }
}