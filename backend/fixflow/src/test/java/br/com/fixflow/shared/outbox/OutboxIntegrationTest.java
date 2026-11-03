package br.com.fixflow.shared.outbox;

import br.com.fixflow.shared.application.outbox.OutboxEvent;
import br.com.fixflow.shared.application.outbox.OutboxService;
import br.com.fixflow.shared.infrastructure.outbox.OutboxPublisher;
import br.com.fixflow.shared.infrastructure.tenant.TenantScope;
import br.com.fixflow.shared.infrastructure.tenant.TenantTransaction;
import br.com.fixflow.support.MembershipFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration-test")
class OutboxIntegrationTest {
    @Autowired OutboxService outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantTransaction tenantTransaction;
    @Autowired PlatformTransactionManager manager;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;

    @Test
    void requiresOriginTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> outbox.record("work_order", UUID.randomUUID(), "Created", "{}"));
    }

    @Test
    void commitsAtomicallyIsolatesRetriesAndStopsAtFiveAttempts() {
        var tx = new TransactionTemplate(manager);
        try (var fixtures = new MembershipFixtures(environment, encoder)) {
            try {
                var rolledBack = new AtomicReference<UUID>();
                var committed = new AtomicReference<UUID>();
                String table = fixtures.createWriteTable();
                TenantScope.run(fixtures.tenantA, () -> {
                    committed.set(tx.execute(status -> {
                        tenantTransaction.configureCurrentTenant();
                        jdbc.update("UPDATE " + table + " SET full_name = 'Committed'");
                        return outbox.record("work_order", fixtures.userId, "Created", "{}");
                    }));
                    assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
                        tenantTransaction.configureCurrentTenant();
                        jdbc.update("UPDATE " + table + " SET full_name = 'Rollback'");
                        rolledBack.set(outbox.record("work_order", fixtures.userId, "Created", "{}"));
                        throw new IllegalStateException();
                    }));
                    tx.executeWithoutResult(status -> {
                        tenantTransaction.configureCurrentTenant();
                        assertEquals("Committed", jdbc.queryForObject("SELECT full_name FROM " + table, String.class));
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE id = ?", Integer.class, rolledBack.get()));
                    });
                });
                TenantScope.run(fixtures.tenantB, () -> tx.executeWithoutResult(status -> {
                    tenantTransaction.configureCurrentTenant();
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE id = ?", Integer.class, committed.get()));
                }));
                ApplicationEventPublisher failure = event -> { throw new IllegalStateException("temporary"); };
                var failing = new OutboxPublisher(jdbc, failure, tenantTransaction, manager);
                failing.publishPending();
                TenantScope.run(fixtures.tenantA, () -> tx.executeWithoutResult(status -> {
                    tenantTransaction.configureCurrentTenant();
                    assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, committed.get()));
                    assertTrue(jdbc.queryForObject("SELECT next_attempt_at > clock_timestamp() FROM outbox_event WHERE id = ?", Boolean.class, committed.get()));
                }));
                for (int attempt = 2; attempt <= 5; attempt++) {
                    makeDue(fixtures.tenantA, committed.get());
                    failing.publishPending();
                }
                TenantScope.run(fixtures.tenantA, () -> tx.executeWithoutResult(status -> {
                    tenantTransaction.configureCurrentTenant();
                    assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, committed.get()));
                    jdbc.update("UPDATE outbox_event SET status = 'PENDING', attempts = 0, next_attempt_at = clock_timestamp() WHERE id = ?", committed.get());
                }));
                publisher.publishPending();
                TenantScope.run(fixtures.tenantA, () -> tx.executeWithoutResult(status -> {
                    tenantTransaction.configureCurrentTenant();
                    assertEquals("PROCESSED", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, committed.get()));
                    assertNotNull(jdbc.queryForObject("SELECT processed_at FROM outbox_event WHERE id = ?", java.sql.Timestamp.class, committed.get()));
                }));
            } finally { cleanup(fixtures.tenantA); }
        }
    }

    @Test
    void concurrentPublisherSkipsLockedEventAndRetainsTenantContext() throws Exception {
        try (var fixtures = new MembershipFixtures(environment, encoder)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var id = new AtomicReference<UUID>();
            TenantScope.run(fixtures.tenantA, () -> id.set(new TransactionTemplate(manager).execute(status ->
                    outbox.record("work_order", fixtures.userId, "Created", "{}"))));
            ApplicationEventPublisher blocking = event -> {
                if (((OutboxEvent) event).id().equals(id.get())) {
                    assertEquals(fixtures.tenantA, br.com.fixflow.shared.infrastructure.tenant.TenantContext.requireTenantId());
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                }
            };
            var worker = new OutboxPublisher(jdbc, blocking, tenantTransaction, manager);
            try (var executor = Executors.newFixedThreadPool(2)) {
                try {
                    var first = executor.submit(worker::publishPending);
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    executor.submit(publisher::publishPending).get(5, TimeUnit.SECONDS);
                    release.countDown();
                    first.get(5, TimeUnit.SECONDS);
                } finally { release.countDown(); }
            } finally { cleanup(fixtures.tenantA); }
        }
    }

    private void makeDue(UUID tenant, UUID id) {
        TenantScope.run(tenant, () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            tenantTransaction.configureCurrentTenant();
            jdbc.update("UPDATE outbox_event SET next_attempt_at = clock_timestamp() WHERE id = ?", id);
        }));
    }

    private void cleanup(UUID tenant) {
        var owner = new JdbcTemplate(new DriverManagerDataSource(
                environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password")));
        owner.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            connection.setAutoCommit(false);
            try (var setting = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                 var delete = connection.prepareStatement("DELETE FROM outbox_event WHERE tenant_id = ?")) {
                setting.setString(1, tenant.toString());
                setting.execute();
                delete.setObject(1, tenant);
                delete.executeUpdate();
                connection.commit();
            } finally { connection.setAutoCommit(true); }
            return null;
        });
    }
}
