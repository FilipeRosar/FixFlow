
package br.com.fixflow.shared.tenant;

import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.infrastructure.tenant.TenantMembershipAuthorizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("integration-test")
@Transactional
class TenantMembershipAuthorizationRepositoryIntegrationTest {

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder encoder;

    private br.com.fixflow.support.MembershipFixtures fixtures;
    private UUID USER_ID;
    private UUID TENANT_A;
    private UUID TENANT_B;

    @org.springframework.test.context.transaction.BeforeTransaction
    void createFixtures() {
        fixtures = new br.com.fixflow.support.MembershipFixtures(environment, encoder);
        USER_ID = fixtures.userId;
        TENANT_A = fixtures.tenantA;
        TENANT_B = fixtures.tenantB;
    }

    @org.springframework.test.context.transaction.AfterTransaction
    void clearFixtures() {
        if (fixtures != null) fixtures.close();
    }

    @Autowired
    private TenantMembershipAuthorizationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRejectUnknownMembership() {
        var result = repository.authorize(
                UUID.randomUUID(),
                TENANT_A
        );

        assertTrue(result.isEmpty());
    }


    @Test
    void shouldRejectNullArguments() {
        var nullUserException = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> repository.authorize(null, TENANT_A)
        );

        assertInstanceOf(
                IllegalArgumentException.class,
                nullUserException.getCause()
        );

        var nullTenantException = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> repository.authorize(USER_ID, null)
        );

        assertInstanceOf(
                IllegalArgumentException.class,
                nullTenantException.getCause()
        );
    }


    @Test
    void shouldPreserveTenantContext() {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true)",
                String.class,
                TENANT_B.toString()
        );

        repository.authorize(USER_ID, TENANT_A);

        String currentTenant = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.tenant_id', true)",
                String.class
        );

        assertEquals(TENANT_B.toString(), currentTenant);
    }

    @Test
    void shouldUseRuntimeDatabaseRole() {
        String role = jdbcTemplate.queryForObject(
                "SELECT current_user",
                String.class
        );

        assertEquals("fixflow_runtime", role);
   }

    @Test
    void shouldAuthorizeActiveMembership() {
        var result = repository.authorize(USER_ID, TENANT_A);

        assertTrue(result.isPresent());

        var membership = result.orElseThrow();

        assertEquals(USER_ID, membership.userId());
        assertEquals(TENANT_A, membership.tenantId());
        assertEquals(
                AuthorizedMembership.Role.OWNER,
                membership.role()
        );
    }

    @Test
    void shouldRejectSuspendedMembership() {
        var result = repository.authorize(USER_ID, TENANT_B);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldRestoreContextAfterSuccessfulAuthorization() {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true)",
                String.class,
                TENANT_B.toString()
        );

        var result = repository.authorize(USER_ID, TENANT_A);

        assertTrue(result.isPresent());

        String currentTenant = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.tenant_id', true)",
                String.class
        );

        assertEquals(TENANT_B.toString(), currentTenant);

        Integer visibleMemberships = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.tenant_memberships
                WHERE user_id = ?
                  AND tenant_id = ?
                """,
                Integer.class,
                USER_ID,
                TENANT_A
        );

        assertEquals(0, visibleMemberships);
    }

}
