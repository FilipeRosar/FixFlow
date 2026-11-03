
package br.com.fixflow.shared.tenant;

import br.com.fixflow.shared.infrastructure.security.AuthenticatedIdentity;
import br.com.fixflow.shared.infrastructure.tenant.AuthorizedTenantExecutor;
import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration-test")
class AuthorizedTenantExecutorIntegrationTest {

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder encoder;

    private br.com.fixflow.support.MembershipFixtures fixtures;
    private UUID TENANT_A;
    private UUID TENANT_B;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        fixtures = new br.com.fixflow.support.MembershipFixtures(environment, encoder);
        TENANT_A = fixtures.tenantA;
        TENANT_B = fixtures.tenantB;
        authenticate(new AuthenticatedIdentity(fixtures.userId, fixtures.email));
    }

    private void authenticate(AuthenticatedIdentity identity) {
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(identity, null, java.util.List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        TenantContext.clearTenantId();
        if (fixtures != null) fixtures.close();
    }

    @Autowired
    private AuthorizedTenantExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private br.com.fixflow.shared.infrastructure.tenant.TenantTransaction tenantTransaction;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void shouldRestoreOuterTransactionAndTenantAfterSuccessAndFailure() {
        var outerTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        br.com.fixflow.shared.infrastructure.tenant.TenantScope.run(TENANT_B, () ->
                outerTransaction.executeWithoutResult(status -> {
                    tenantTransaction.configureCurrentTenant();
                    int outerPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    executor.execute(TENANT_A, membership -> {
                        assertNotEquals(outerPid, jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        assertEquals(TENANT_A.toString(), jdbcTemplate.queryForObject(
                                "SELECT current_setting('app.tenant_id', true)", String.class));
                        return null;
                    });
                    assertThrows(IllegalStateException.class, () -> executor.execute(TENANT_A, membership -> {
                        throw new IllegalStateException("inner operation failed");
                    }));
                    assertEquals(TENANT_B, TenantContext.requireTenantId());
                    assertEquals(outerPid, jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    assertEquals(TENANT_B.toString(), jdbcTemplate.queryForObject(
                            "SELECT current_setting('app.tenant_id', true)", String.class));
                    status.setRollbackOnly();
                }));
        assertTrue(TenantContext.getTenantId().isEmpty());
        assertNull(jdbcTemplate.queryForObject("SELECT NULLIF(current_setting('app.tenant_id', true), '')", String.class));
    }

    @Test
    void shouldShareAuthorizedTransactionBetweenJpaAndJdbc() {
        executor.execute(TENANT_A, membership -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(TENANT_A, TenantContext.requireTenantId());
            var jpaConnection = (Number) entityManager.createNativeQuery("SELECT pg_backend_pid()")
                    .getSingleResult();
            var jdbcConnection = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
            assertEquals(jpaConnection.intValue(), jdbcConnection);
            assertEquals(java.util.List.of(TENANT_A), jdbcTemplate.queryForList("SELECT id FROM tenants", UUID.class));
            assertEquals(java.util.List.of(TENANT_A), entityManager.createNativeQuery("SELECT id FROM tenants")
                    .getResultList());
            return null;
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TenantContext.getTenantId().isEmpty());
        assertNull(jdbcTemplate.queryForObject("SELECT NULLIF(current_setting('app.tenant_id', true), '')", String.class));
    }

    @Test
    void shouldRejectUnknownTenantWithoutExecutingOperation() {
        assertThrows(AccessDeniedException.class, () -> executor.execute(UUID.randomUUID(), membership -> {
            fail("Operação não autorizada foi executada");
            return null;
        }));
        assertTrue(TenantContext.getTenantId().isEmpty());
    }

    @Test
    void shouldRequireAuthenticatedSecurityIdentity() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThrows(org.springframework.security.authentication.InsufficientAuthenticationException.class,
                () -> executor.execute(TENANT_A, membership -> fail("Operação sem autenticação")));
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated("client-supplied-id", null, java.util.List.of()));
        assertThrows(org.springframework.security.authentication.InsufficientAuthenticationException.class,
                () -> executor.execute(TENANT_A, membership -> fail("Principal inválido")));
    }

    @Test
    void shouldCleanContextAfterOperationThrows() {
        var failure = new IllegalStateException("operation failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> executor.execute(TENANT_A, membership -> { throw failure; })));
        assertTrue(TenantContext.getTenantId().isEmpty());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertNull(jdbcTemplate.queryForObject("SELECT NULLIF(current_setting('app.tenant_id', true), '')", String.class));
        assertEquals(TENANT_A, executor.execute(TENANT_A, membership -> TenantContext.requireTenantId()));
    }

    @Test
    void shouldExecuteInsideAuthorizedTenant() {
        UUID visibleTenant = executor.execute(
                TENANT_A,
                membership -> {
                    assertEquals(TENANT_A, membership.tenantId());

                    return jdbcTemplate.queryForObject(
                            """
                            SELECT NULLIF(
                                current_setting('app.tenant_id', true),
                                ''
                            )::uuid
                            """,
                            UUID.class
                    );
                }
        );

        assertEquals(TENANT_A, visibleTenant);
        assertTrue(TenantContext.getTenantId().isEmpty());
    }

    @Test
    void shouldRejectSuspendedMembership() {
        assertThrows(
                AccessDeniedException.class,
                () -> executor.execute(
                                TENANT_B,
                        membership -> "NUNCA_EXECUTAR"
                )
        );

        assertTrue(TenantContext.getTenantId().isEmpty());
    }

    @Test
    void shouldRejectUnknownUser() {
        var unknownIdentity = new AuthenticatedIdentity(
                UUID.randomUUID(),
                "unknown@example.invalid"
        );

        authenticate(unknownIdentity);
        assertThrows(
                AccessDeniedException.class,
                () -> executor.execute(
                        TENANT_A,
                        membership -> "NUNCA_EXECUTAR"
                )
        );
    }

    @Test
    void shouldRestorePreviousJavaContext() {
        TenantContext.setTenantId(TENANT_B);

        try {
            UUID result = executor.execute(
                        TENANT_A,
                    membership -> TenantContext.requireTenantId()
            );

            assertEquals(TENANT_A, result);
            assertEquals(
                    TENANT_B,
                    TenantContext.requireTenantId()
            );
        } finally {
            TenantContext.clearTenantId();
        }
    }
}
