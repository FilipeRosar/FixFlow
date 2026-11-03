package br.com.fixflow.shared.tenant;

import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import br.com.fixflow.shared.infrastructure.tenant.TenantDatabaseAccess;
import br.com.fixflow.shared.infrastructure.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import br.com.fixflow.support.MembershipFixtures;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration-test")
class TenantDatabaseAccessIntegrationTest {

    private UUID TENANT_A;
    private UUID TENANT_B;

    @Autowired
    private Environment environment;

    @Autowired
    private PasswordEncoder encoder;

    private MembershipFixtures fixtures;

    @BeforeEach
    void createFixtures() {
        fixtures = new MembershipFixtures(environment, encoder);
        TENANT_A = fixtures.tenantA;
        TENANT_B = fixtures.tenantB;
    }

    @Autowired
    private TenantDatabaseAccess tenantDatabaseAccess;

    @AfterEach
    void clearContext() {
        TenantContext.clearTenantId();
        if (fixtures != null) fixtures.close();
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
