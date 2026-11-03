package br.com.fixflow.shared.audit;

import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.tenant.TenantScope;
import br.com.fixflow.shared.infrastructure.tenant.TenantTransaction;
import br.com.fixflow.support.MembershipFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration-test")
class AuditServiceIntegrationTest {
    @Autowired AuditService audit;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantTransaction tenantTransaction;

    @Test
    void shouldRequireExistingTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> audit.record(null, "CREATE", "work_order", UUID.randomUUID(), "{}"));
    }

    @Test
    void shouldCommitRollbackIsolateAndDenyMutations() {
        var tx = new TransactionTemplate(transactionManager);
        try (var fixtures = new MembershipFixtures(environment, encoder)) {
            String table = fixtures.createWriteTable();
            try {
                TenantScope.run(fixtures.tenantA, () -> {
                    UUID id = tx.execute(status -> {
                        tenantTransaction.configureCurrentTenant();
                        jdbc.update("UPDATE " + table + " SET full_name = 'Committed'");
                        return audit.record(fixtures.userId, "UPDATE", "work_order", fixtures.userId,
                                "{\"status\":\"OPEN\"}");
                    });
                    var rolledBack = new AtomicReference<UUID>();
                    assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
                        tenantTransaction.configureCurrentTenant();
                        jdbc.update("UPDATE " + table + " SET full_name = 'Rolled back'");
                        rolledBack.set(audit.record(null, "UPDATE", "work_order", fixtures.userId, "{}"));
                        throw new IllegalStateException("rollback");
                    }));
                    tx.executeWithoutResult(status -> {
                        tenantTransaction.configureCurrentTenant();
                        assertEquals("Committed", jdbc.queryForObject("SELECT full_name FROM " + table, String.class));
                        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE id = ?", Integer.class, id));
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE id = ?", Integer.class, rolledBack.get()));
                        assertEquals(fixtures.userId, jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE id = ?", UUID.class, id));
                    });
                    for (String sql : new String[]{"UPDATE audit_log SET action = 'FORGED'", "DELETE FROM audit_log", "TRUNCATE audit_log"}) {
                        assertThrows(org.springframework.dao.DataAccessException.class, () -> tx.executeWithoutResult(status -> {
                            tenantTransaction.configureCurrentTenant();
                            jdbc.execute(sql);
                        }));
                    }
                });
                TenantScope.run(fixtures.tenantB, () -> tx.executeWithoutResult(status -> {
                    tenantTransaction.configureCurrentTenant();
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
                    assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
                            INSERT INTO audit_log (tenant_id, action, resource_type, resource_id)
                            VALUES (?, 'FORGED', 'work_order', ?)
                            """, fixtures.tenantA, fixtures.userId));
                    status.setRollbackOnly();
                }));
            } finally {
                // Cleanup through the owner, restricted to this fixture's tenant.
                var owner = new JdbcTemplate(new DriverManagerDataSource(
                        environment.getRequiredProperty("spring.flyway.url"),
                        environment.getRequiredProperty("spring.flyway.user"),
                        environment.getRequiredProperty("spring.flyway.password")));
                // Use a single connection for the transaction-local RLS setting.
                owner.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    connection.setAutoCommit(false);
                    try (var setting = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                         var delete = connection.prepareStatement("DELETE FROM audit_log WHERE tenant_id = ?")) {
                        setting.setString(1, fixtures.tenantA.toString());
                        setting.execute();
                        delete.setObject(1, fixtures.tenantA);
                        delete.executeUpdate();
                        connection.commit();
                    } finally { connection.setAutoCommit(true); }
                    return null;
                });
            }
        }
    }
}
