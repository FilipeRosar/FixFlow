package br.com.fixflow.support;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Uses migration credentials only for fixtures; application queries still use runtime. */
public final class MembershipFixtures implements AutoCloseable {
    public final UUID userId = UUID.randomUUID();
    public final UUID tenantA = UUID.randomUUID();
    public final UUID tenantB = UUID.randomUUID();
    public final String email = userId + "@example.invalid";
    public final String password = "integration-password";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String fixturePolicy = "fixture_" + userId.toString().replace("-", "");
    private String writeTable;
    private boolean closed;

    public MembershipFixtures(Environment environment, PasswordEncoder encoder) {
        String databaseUrl = environment.getRequiredProperty("spring.flyway.url");
        if (!databaseUrl.matches("jdbc:postgresql://[^/]+/fixflow_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Fixtures só podem ser criadas no banco fixflow_test.");
        }
        var dataSource = new DriverManagerDataSource(
                databaseUrl,
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password"));
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        provision(() -> {
            jdbc.update("INSERT INTO users (id, full_name, email, password_hash) VALUES (?, ?, ?, ?)",
                    userId, "Integration identity", email, encoder.encode(password));
            createTenant(tenantA, "ACTIVE");
            createTenant(tenantB, "SUSPENDED");
        });
    }

    private void provision(Runnable operation) {
        transaction.executeWithoutResult(status -> {
            // The owner is also subject to FORCE RLS. Permit only these random fixture
            // memberships while provisioning, and remove the policy before committing.
            // Runtime never receives additional privileges or a relaxed RLS policy.
            jdbc.execute("CREATE POLICY " + fixturePolicy + " ON tenant_memberships FOR ALL TO fixflow_migrator "
                    + "USING (tenant_id IN ('" + tenantA + "'::uuid, '" + tenantB + "'::uuid)) "
                    + "WITH CHECK (tenant_id IN ('" + tenantA + "'::uuid, '" + tenantB + "'::uuid))");
            operation.run();
            jdbc.execute("DROP POLICY " + fixturePolicy + " ON tenant_memberships");
        });
    }

    private void createTenant(UUID tenantId, String membershipStatus) {
        setTenant(tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Integration workshop");
        jdbc.update("""
                INSERT INTO tenant_memberships (user_id, tenant_id, role, status)
                VALUES (?, ?, 'OWNER', ?)
                """, userId, tenantId, membershipStatus);
        jdbc.update("""
                INSERT INTO app_users (tenant_id, full_name, email, password_hash)
                VALUES (?, 'Integration legacy user', ?, 'unused-test-hash')
                """, tenantId, tenantId.equals(tenantA) ? "teste-a@example.com" : "teste-b@example.com");
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    public void suspendUser() {
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", userId);
    }

    public void activateSecondMembership() {
        provision(() -> {
            setTenant(tenantB);
            jdbc.update("UPDATE tenant_memberships SET status = 'ACTIVE' WHERE user_id = ? AND tenant_id = ?",
                    userId, tenantB);
        });
    }

    /** A test-only clone with the actual app_users policies, writable only in fixflow_test. */
    public String createWriteTable() {
        if (writeTable != null) throw new IllegalStateException("Tabela de escrita já criada.");
        writeTable = "p1_app_users_" + userId.toString().replace("-", "");
        transaction.executeWithoutResult(status -> {
            jdbc.execute("CREATE TABLE public." + writeTable + " (LIKE public.app_users INCLUDING ALL)");
            jdbc.execute("ALTER TABLE public." + writeTable + " ENABLE ROW LEVEL SECURITY");
            jdbc.execute("ALTER TABLE public." + writeTable + " FORCE ROW LEVEL SECURITY");
            var policies = jdbc.queryForList("""
                    SELECT policyname, qual, with_check FROM pg_policies
                    WHERE schemaname = 'public' AND tablename = 'app_users'
                      AND cmd = 'ALL' AND permissive = 'PERMISSIVE'
                      AND roles = ARRAY['public']::name[]
                    """);
            if (policies.size() != 1) throw new IllegalStateException("Revisar policies de app_users antes de copiar.");
            var policy = policies.getFirst();
            jdbc.execute("CREATE POLICY tenant_isolation ON public." + writeTable
                    + " USING (" + policy.get("qual") + ") WITH CHECK (" + policy.get("with_check") + ")");
            jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public." + writeTable + " TO fixflow_runtime");
            for (var tenantId : new UUID[]{tenantA, tenantB}) {
                setTenant(tenantId);
                jdbc.update("INSERT INTO public." + writeTable + " (tenant_id, full_name, email, password_hash) "
                        + "VALUES (?, 'Initial', ?, 'unused-test-hash')", tenantId, tenantId + "@example.invalid");
            }
        });
        return "public." + writeTable;
    }

    public void suspendTenant() {
        transaction.executeWithoutResult(status -> {
            setTenant(tenantA);
            jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", tenantA);
        });
    }

    @Override
    public void close() {
        if (closed) return;
        provision(() -> {
            if (writeTable != null) jdbc.execute("DROP TABLE IF EXISTS public." + writeTable);
            var migratedUsers = jdbc.queryForList("""
                    SELECT DISTINCT user_id FROM legacy_user_identity_migrations
                    WHERE tenant_id IN (?, ?) AND created_identity
                    """, UUID.class, tenantA, tenantB);
            for (var tenantId : new UUID[]{tenantA, tenantB}) {
                setTenant(tenantId);
                jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id = ?", tenantId);
                jdbc.update("DELETE FROM legacy_user_identity_migrations WHERE tenant_id = ?", tenantId);
                jdbc.update("DELETE FROM app_users WHERE tenant_id = ?", tenantId);
                jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
            }
            for (var migratedUser : migratedUsers) {
                jdbc.update("""
                        DELETE FROM users u WHERE id = ?
                        AND NOT EXISTS (SELECT 1 FROM tenant_memberships m WHERE m.user_id = u.id)
                        AND NOT EXISTS (SELECT 1 FROM legacy_user_identity_migrations m WHERE m.user_id = u.id)
                        """, migratedUser);
            }
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        });
        closed = true;
    }
}
