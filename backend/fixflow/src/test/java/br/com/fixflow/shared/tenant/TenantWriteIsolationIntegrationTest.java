package br.com.fixflow.shared.tenant;

import br.com.fixflow.shared.infrastructure.security.AuthenticatedIdentity;
import br.com.fixflow.shared.infrastructure.tenant.AuthorizedTenantExecutor;
import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import br.com.fixflow.support.MembershipFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
@ActiveProfiles("integration-test")
class TenantWriteIsolationIntegrationTest {
    enum Access { JDBC, JPA }

    @Autowired private Environment environment;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AuthorizedTenantExecutor executor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    private MembershipFixtures fixtures;
    private String table;

    @BeforeEach
    void setUp() {
        fixtures = new MembershipFixtures(environment, encoder);
        fixtures.activateSecondMembership();
        table = fixtures.createWriteTable();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedIdentity(fixtures.userId, fixtures.email), null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        try {
            assertCleanConnection();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clearTenantId();
            if (fixtures != null) fixtures.close();
        }
    }

    private int execute(Access access, String sql, Object... arguments) {
        if (access == Access.JDBC) return jdbc.update(sql, arguments);
        // JDBC placeholders also use positional parameters with Hibernate native queries.
        var query = entityManager.createNativeQuery(sql);
        for (int index = 0; index < arguments.length; index++) query.setParameter(index + 1, arguments[index]);
        return query.executeUpdate();
    }

    private List<?> visibleTenants(Access access) {
        String sql = "SELECT tenant_id FROM " + table;
        return access == Access.JDBC ? jdbc.queryForList(sql, UUID.class)
                : entityManager.createNativeQuery(sql).getResultList();
    }

    private int backendPid() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private void assertCleanConnection() {
        assertTrue(TenantContext.getTenantId().isEmpty());
        assertNull(jdbc.queryForObject("SELECT NULLIF(current_setting('app.tenant_id', true), '')", String.class));
        if (table != null) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }

    private SQLException assertRlsViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                assertEquals("42501", sqlException.getSQLState());
                return sqlException;
            }
        }
        return fail("Esperada violação SQL de permissão ou RLS", exception);
    }

    @ParameterizedTest
    @EnumSource(Access.class)
    void shouldIsolateReadsUpdatesAndDeletesInBothDirections(Access access) {
        for (UUID tenant : List.of(fixtures.tenantA, fixtures.tenantB)) {
            UUID other = tenant.equals(fixtures.tenantA) ? fixtures.tenantB : fixtures.tenantA;
            executor.execute(tenant, membership -> {
                assertEquals(List.of(tenant), visibleTenants(access));
                assertEquals(0, execute(access, "UPDATE " + table + " SET full_name = 'cross-tenant' WHERE tenant_id = ?", other));
                assertEquals(0, execute(access, "DELETE FROM " + table + " WHERE tenant_id = ?", other));
                assertEquals(1, execute(access, "UPDATE " + table + " SET full_name = 'Own workshop' WHERE tenant_id = ?", tenant));
                return null;
            });
        }
        for (UUID tenant : List.of(fixtures.tenantA, fixtures.tenantB)) {
            assertEquals("Own workshop", executor.execute(tenant, membership ->
                    jdbc.queryForObject("SELECT full_name FROM " + table, String.class)));
        }
    }

    @ParameterizedTest
    @EnumSource(Access.class)
    void shouldCommitOwnInsertAndDelete(Access access) {
        UUID id = UUID.randomUUID();
        executor.execute(fixtures.tenantA, membership -> {
            assertEquals(1, execute(access, "INSERT INTO " + table
                    + " (id, tenant_id, full_name, email, password_hash) VALUES (?, ?, 'Created', 'created@example.invalid', 'hash')",
                    id, membership.tenantId()));
            return null;
        });
        assertEquals(2, executor.<Integer>execute(fixtures.tenantA, membership ->
                jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)));
        executor.execute(fixtures.tenantA, membership -> {
            assertEquals(1, execute(access, "DELETE FROM " + table + " WHERE id = ?", id));
            return null;
        });
        assertEquals(1, executor.<Integer>execute(fixtures.tenantA, membership ->
                jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)));
    }

    @ParameterizedTest
    @EnumSource(Access.class)
    void shouldRejectCrossTenantInsertInBothDirections(Access access) {
        for (UUID tenant : List.of(fixtures.tenantA, fixtures.tenantB)) {
            UUID other = tenant.equals(fixtures.tenantA) ? fixtures.tenantB : fixtures.tenantA;
            var failure = assertThrows(RuntimeException.class, () -> executor.execute(tenant, membership ->
                    execute(access, "INSERT INTO " + table
                            + " (tenant_id, full_name, email, password_hash) VALUES (?, 'Forbidden', 'forbidden@example.invalid', 'hash')", other)));
            assertRlsViolation(failure);
            assertCleanConnection();
            assertEquals(1, executor.<Integer>execute(tenant, membership ->
                    jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)));
        }
    }

    @ParameterizedTest
    @EnumSource(Access.class)
    void shouldRejectMovingRowToAnotherTenant(Access access) {
        var failure = assertThrows(RuntimeException.class, () -> executor.execute(fixtures.tenantA, membership ->
                execute(access, "UPDATE " + table + " SET tenant_id = ?", fixtures.tenantB)));
        assertRlsViolation(failure);
        assertEquals(List.of(fixtures.tenantA), executor.execute(fixtures.tenantA, membership -> visibleTenants(access)));
    }

    @ParameterizedTest
    @EnumSource(Access.class)
    void shouldRejectWritesWithoutTenant(Access access) {
        var transaction = new TransactionTemplate(transactionManager);
        var failure = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status ->
                execute(access, "INSERT INTO " + table
                        + " (tenant_id, full_name, email, password_hash) VALUES (?, 'Forbidden', 'no-context@example.invalid', 'hash')",
                        fixtures.tenantA)));
        assertRlsViolation(failure);
    }

    @ParameterizedTest
    @EnumSource(Access.class)
    void shouldRollBackWritesAndReuseConnectionAfterException(Access access) {
        int originalPid = backendPid();
        var failure = new IllegalStateException("Rollback the operation");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> executor.execute(fixtures.tenantA, membership -> {
            assertEquals(originalPid, backendPid());
            execute(access, "UPDATE " + table + " SET full_name = 'Must roll back'");
            execute(access, "INSERT INTO " + table
                    + " (tenant_id, full_name, email, password_hash) VALUES (?, 'Rollback', 'rollback@example.invalid', 'hash')",
                    fixtures.tenantA);
            throw failure;
        })));
        assertCleanConnection();
        assertEquals(originalPid, backendPid());
        executor.execute(fixtures.tenantA, membership -> {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            assertEquals("Initial", jdbc.queryForObject("SELECT full_name FROM " + table, String.class));
            return null;
        });
    }

    @Test
    void shouldRollBackAfterSqlErrorAndRecoverSameConnection() {
        int originalPid = backendPid();
        assertThrows(RuntimeException.class, () -> executor.execute(fixtures.tenantA, membership -> {
            jdbc.update("UPDATE " + table + " SET full_name = 'Must roll back'");
            return jdbc.queryForObject("SELECT 1 / 0", Integer.class);
        }));
        assertCleanConnection();
        assertEquals(originalPid, backendPid());
        assertEquals("Initial", executor.execute(fixtures.tenantA, membership ->
                jdbc.queryForObject("SELECT full_name FROM " + table, String.class)));
    }

    @Test
    void shouldRollBackAndClearContextWhenErrorEscapes() {
        var failure = new AssertionError("Simulated operation error");
        assertSame(failure, assertThrows(AssertionError.class, () -> executor.execute(fixtures.tenantA, membership -> {
            jdbc.update("UPDATE " + table + " SET full_name = 'Must roll back'");
            throw failure;
        })));
        assertCleanConnection();
        assertEquals("Initial", executor.execute(fixtures.tenantA, membership ->
                jdbc.queryForObject("SELECT full_name FROM " + table, String.class)));
    }

    @Test
    void shouldNotLeakTenantAcrossRepeatedUsesOfSamePhysicalConnection() {
        int originalPid = backendPid();
        for (int iteration = 0; iteration < 3; iteration++) {
            for (UUID tenant : List.of(fixtures.tenantA, fixtures.tenantB)) {
                executor.execute(tenant, membership -> {
                    assertEquals(originalPid, backendPid());
                    assertEquals(List.of(tenant), visibleTenants(Access.JPA));
                    assertEquals(List.of(tenant), visibleTenants(Access.JDBC));
                    return null;
                });
                assertCleanConnection();
                assertEquals(originalPid, backendPid());
            }
        }
    }

    @Test
    void shouldKeepProductionTablesReadOnlyForRuntime() {
        assertFalse(jdbc.queryForObject("""
                SELECT has_table_privilege(current_user, 'public.app_users', 'INSERT, UPDATE, DELETE')
                    OR has_table_privilege(current_user, 'public.tenants', 'INSERT, UPDATE, DELETE')
                """, Boolean.class));
        var failure = assertThrows(RuntimeException.class, () -> executor.execute(fixtures.tenantA, membership ->
                jdbc.update("UPDATE public.app_users SET full_name = 'Forbidden' WHERE tenant_id = ?", fixtures.tenantA)));
        assertTrue(assertRlsViolation(failure).getMessage().contains("permission denied for table app_users"));
    }
}
