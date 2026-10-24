package br.com.fixflow.shared.tenant;

import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import br.com.fixflow.shared.infrastructure.tenant.TenantDatabaseAccess;
import br.com.fixflow.shared.infrastructure.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TenantDatabaseAccessIntegrationTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Autowired
    private TenantDatabaseAccess tenantDatabaseAccess;

    @AfterEach
    void clearContext() {
        TenantContext.clearTenantId();
    }

    @Test
    void shouldReturnOnlyTheCurrentTenant() {
        TenantScope.run(TENANT_A, () -> {
            List<UUID> visibleTenants =
                    tenantDatabaseAccess.findVisibleTenantsIds();

            assertEquals(List.of(TENANT_A), visibleTenants);
            assertFalse(visibleTenants.contains(TENANT_B));
        });
    }

    @Test
    void shouldRejectDatabaseAccessWithoutTenantContext() {
        assertTrue(TenantContext.getTenantId().isEmpty());

        assertThrows(
                IllegalStateException.class,
                () -> tenantDatabaseAccess.findVisibleTenantsIds()
        );
    }
    @Test
    void shouldReturnOnlyUsersFromTheCurrentTenant() {
        TenantScope.run(TENANT_A, () -> {
            List<String> visibleEmails =
                    tenantDatabaseAccess.findVisibleUserEmails();

            assertEquals(
                    List.of("teste-a@example.com"),
                    visibleEmails
            );

            assertFalse(
                    visibleEmails.contains("teste-b@example.com")
            );
        });
    }

}