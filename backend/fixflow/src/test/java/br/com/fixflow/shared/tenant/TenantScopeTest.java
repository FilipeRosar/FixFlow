package br.com.fixflow.shared.tenant;

import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import br.com.fixflow.shared.infrastructure.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TenantScopeTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @AfterEach
    void clearContext() {
        TenantContext.clearTenantId();
    }

    @Test
    void shouldSetAndClearTenantWhenNoPreviousContextExists() {
        assertTrue(TenantContext.getTenantId().isEmpty());

        TenantScope.run(TENANT_A, () -> {
            assertEquals(TENANT_A, TenantContext.requireTenantId());
        });

        assertTrue(TenantContext.getTenantId().isEmpty());
    }

    @Test
    void shouldRestorePreviousTenantAfterNestedScope() {
        TenantContext.setTenantId(TENANT_A);

        TenantScope.run(TENANT_B, () -> {
            assertEquals(TENANT_B, TenantContext.requireTenantId());
        });

        assertEquals(TENANT_A, TenantContext.requireTenantId());
    }

    @Test
    void shouldRestorePreviousTenantWhenTaskThrowsException() {
        TenantContext.setTenantId(TENANT_A);

        assertThrows(
                IllegalStateException.class,
                () -> TenantScope.run(TENANT_B, () -> {
                    assertEquals(TENANT_B, TenantContext.requireTenantId());
                    throw new IllegalStateException("Erro simulado");
                })
        );

        assertEquals(TENANT_A, TenantContext.requireTenantId());
    }

    @Test
    void shouldRejectNullTenant() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TenantScope.run(null, () -> {})
        );
    }
}