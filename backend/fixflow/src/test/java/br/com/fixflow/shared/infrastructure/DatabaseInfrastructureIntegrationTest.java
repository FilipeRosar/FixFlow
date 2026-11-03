package br.com.fixflow.shared.infrastructure;

import br.com.fixflow.support.MembershipFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration-test")
class DatabaseInfrastructureIntegrationTest {
    @Autowired private Environment environment;
    @Autowired private PasswordEncoder encoder;
    @Autowired private Flyway flyway;

    private DriverManagerDataSource migrator(String url) {
        return new DriverManagerDataSource(url,
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password"));
    }

    @Test
    void shouldHaveSuccessfulV8AndReadGrantsInBothDatabases() {
        var expected = Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getVersion() != null && "8".equals(migration.getVersion().toString()))
                .findFirst().orElseThrow();
        String testUrl = environment.getRequiredProperty("spring.flyway.url");
        for (String database : List.of("fixflow", "fixflow_test")) {
            String url = testUrl.replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
            var jdbc = new JdbcTemplate(migrator(url));
            var row = jdbc.queryForMap("""
                    SELECT script, checksum, success FROM public.flyway_schema_history WHERE version = '8'
                    """);
            assertEquals("V8__grant_tenant_read_access.sql", row.get("script"), database);
            assertEquals(Boolean.TRUE, row.get("success"), database);
            assertEquals(expected.getChecksum(), row.get("checksum"), database);
            assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                    SELECT has_table_privilege('fixflow_runtime', 'public.tenants', 'SELECT')
                       AND has_table_privilege('fixflow_runtime', 'public.app_users', 'SELECT')
                    """, Boolean.class), database);
            assertEquals(2, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname IN ('tenants', 'app_users')
                      AND c.relrowsecurity AND c.relforcerowsecurity
                    """, Integer.class), database);
            System.out.printf("V8 confirmada: banco=%s, checksum=%s, grants SELECT=OK%n", database, row.get("checksum"));
        }
    }

    @Test
    void shouldKeepRuntimeWithoutAdministrativePrivilegesInBothDatabases() {
        for (String database : List.of("fixflow", "fixflow_test")) {
            String url = environment.getRequiredProperty("spring.flyway.url")
                    .replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
            var jdbc = new JdbcTemplate(migrator(url));
            assertFalse(jdbc.queryForObject("""
                    SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls
                    FROM pg_roles WHERE rolname = 'fixflow_runtime'
                    """, Boolean.class), database);
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_roles WHERE rolname <> 'fixflow_runtime'
                    AND pg_has_role('fixflow_runtime', oid, 'MEMBER')
                    """, Integer.class), database);
            assertFalse(jdbc.queryForObject("""
                    SELECT has_schema_privilege('fixflow_runtime', 'public', 'CREATE')
                        OR has_database_privilege('fixflow_runtime', current_database(), 'CREATE')
                    """, Boolean.class), database);
            assertFalse(jdbc.queryForObject("""
                    SELECT has_table_privilege('fixflow_runtime', 'public.users', 'SELECT, INSERT, UPDATE, DELETE')
                        OR has_table_privilege('fixflow_runtime', 'public.legacy_user_identity_migrations', 'SELECT, INSERT, UPDATE, DELETE')
                        OR has_function_privilege('fixflow_runtime', 'public.backfill_legacy_app_users()', 'EXECUTE')
                    """, Boolean.class), database);
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_namespace WHERE nspname = 'public'
                    AND nspowner = (SELECT oid FROM pg_roles WHERE rolname = 'fixflow_runtime')
                    """, Integer.class), database);
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace
                    AND relowner = (SELECT oid FROM pg_roles WHERE rolname = 'fixflow_runtime')
                    """, Integer.class), database);
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_database WHERE datname = current_database()
                    AND datdba = (SELECT oid FROM pg_roles WHERE rolname = 'fixflow_runtime')
                    """, Integer.class), database);
            System.out.printf("Runtime auditado: banco=%s, admin=NAO, BYPASSRLS=NAO, memberships=0, ownership=0%n", database);
        }
    }

    @Test
    void shouldHaveSuccessfulV9AndNoMigrationPoliciesInBothDatabases() {
        var expected = Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getVersion() != null && "9".equals(migration.getVersion().toString()))
                .findFirst().orElseThrow();
        for (String database : List.of("fixflow", "fixflow_test")) {
            var jdbc = new JdbcTemplate(migrator(environment.getRequiredProperty("spring.flyway.url")
                    .replaceFirst("/[^/?]+(?=\\?|$)", "/" + database)));
            var row = jdbc.queryForMap("SELECT checksum, success FROM flyway_schema_history WHERE version = '9'");
            assertEquals(expected.getChecksum(), row.get("checksum"), database);
            assertEquals(true, row.get("success"), database);
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public'
                    AND policyname IN ('f02_legacy_read', 'f02_legacy_memberships')
                    """, Integer.class), database);
        }
    }

    @Test
    void shouldCleanFixturesAndWriteTableEvenWhenTestBodyThrows() {
        var fixture = new MembershipFixtures(environment, encoder);
        var table = new java.util.concurrent.atomic.AtomicReference<String>();
        var failure = new IllegalStateException("Simulated test failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> {
            try (fixture) {
                table.set(fixture.createWriteTable());
                throw failure;
            }
        }));
        fixture.close(); // Closing twice must be harmless.
        var dataSource = migrator(environment.getRequiredProperty("spring.flyway.url"));
        var jdbc = new JdbcTemplate(dataSource);
        assertNull(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table.get()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, fixture.userId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pg_policies WHERE policyname = ?",
                Integer.class, "fixture_" + fixture.userId.toString().replace("-", "")));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        for (var tenant : List.of(fixture.tenantA, fixture.tenantB)) {
            transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class, tenant));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE tenant_id = ?", Integer.class, tenant));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_memberships WHERE tenant_id = ?", Integer.class, tenant));
            });
        }
    }
}
