
package br.com.fixflow.shared.infrastructure.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @AfterEach
    void cleanUp() {
        TenantContext.clearTenantId();
    }

    @Test
    void shouldReturnEmptyWhenNoTenantIsSet() {
        assertTrue(TenantContext.getTenantId().isEmpty());
    }

    @Test
    void shouldStoreAndReturnTenantId() {
        TenantContext.setTenantId(TENANT_A);

        assertEquals(TENANT_A, TenantContext.requireTenantId());
    }

    @Test
    void shouldRejectNullTenantId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TenantContext.setTenantId(null)
        );
    }

    @Test
    void shouldThrowWhenTenantIsRequiredButMissing() {
        assertThrows(
                IllegalStateException.class,
                TenantContext::requireTenantId
        );
    }

    @Test
    void shouldClearTenantId() {
        TenantContext.setTenantId(TENANT_A);

        TenantContext.clearTenantId();

        assertTrue(TenantContext.getTenantId().isEmpty());
    }
}
