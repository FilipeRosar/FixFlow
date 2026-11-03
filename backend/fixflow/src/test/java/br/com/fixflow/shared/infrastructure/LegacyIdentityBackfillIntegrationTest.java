package br.com.fixflow.shared.infrastructure;

import br.com.fixflow.support.MembershipFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration-test")
class LegacyIdentityBackfillIntegrationTest {
    @Autowired private Environment environment;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate runtime;
    private JdbcTemplate migrator;
    private TransactionTemplate transaction;
    private MembershipFixtures fixture;

    @BeforeEach
    void setUp() {
        fixture = new MembershipFixtures(environment, encoder);
        var dataSource = new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password"));
        migrator = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) fixture.close();
    }

    private void inTenant(UUID tenant, Consumer<JdbcTemplate> operation) {
        transaction.executeWithoutResult(status -> {
            migrator.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            operation.accept(migrator);
        });
    }

    private Map<String, Object> backfill() {
        return migrator.queryForMap("SELECT * FROM public.backfill_legacy_app_users()");
    }

    private List<Map<String, Object>> migratedIdentities() {
        return migrator.queryForList("""
                SELECT u.id, u.email, u.status, u.password_hash, m.requires_password_reset,
                       m.created_identity, m.created_membership
                FROM legacy_user_identity_migrations m JOIN users u ON u.id = m.user_id
                WHERE m.tenant_id IN (?, ?) ORDER BY m.tenant_id
                """, fixture.tenantA, fixture.tenantB);
    }

    private void assertNoTemporaryPolicies() {
        assertEquals(0, migrator.queryForObject("""
                SELECT COUNT(*) FROM pg_policies
                WHERE schemaname = 'public' AND policyname IN ('f02_legacy_read', 'f02_legacy_memberships')
                """, Integer.class));
    }

    @Test
    void shouldQuarantineInvalidCredentialsAndPreserveLegacyRows() {
        assertEquals(Map.of("migrated_records", 2L, "created_identities", 2L,
                "created_memberships", 2L, "reset_required", 2L), backfill());
        for (var identity : migratedIdentities()) {
            assertEquals("SUSPENDED", identity.get("status"));
            assertEquals("!RESET_REQUIRED!", identity.get("password_hash"));
            assertEquals(true, identity.get("requires_password_reset"));
        }
        for (UUID tenant : List.of(fixture.tenantA, fixture.tenantB)) {
            inTenant(tenant, jdbc -> {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE tenant_id = ?", Integer.class, tenant));
                assertEquals("unused-test-hash", jdbc.queryForObject("SELECT password_hash FROM app_users WHERE tenant_id = ?", String.class, tenant));
                assertEquals(0, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM tenant_memberships m JOIN legacy_user_identity_migrations l
                        ON l.user_id = m.user_id AND l.tenant_id = m.tenant_id
                        WHERE m.tenant_id = ? AND (m.role <> 'VIEWER' OR m.status <> 'INVITED')
                        """, Integer.class, tenant));
            });
        }
        assertNoTemporaryPolicies();
    }

    @Test
    void shouldPreserveValidBcryptAndMergeMatchingEmailAcrossWorkshops() {
        String hash = encoder.encode("legacy-password");
        String email = UUID.randomUUID() + "@example.invalid";
        inTenant(fixture.tenantA, jdbc -> jdbc.update("UPDATE app_users SET email = ?, password_hash = ? WHERE tenant_id = ?",
                email.toUpperCase(), hash, fixture.tenantA));
        inTenant(fixture.tenantB, jdbc -> jdbc.update("UPDATE app_users SET email = ?, password_hash = ? WHERE tenant_id = ?",
                email, hash, fixture.tenantB));
        assertEquals(Map.of("migrated_records", 2L, "created_identities", 1L,
                "created_memberships", 2L, "reset_required", 0L), backfill());
        var identities = migratedIdentities();
        assertEquals(2, identities.size());
        assertEquals(identities.getFirst().get("id"), identities.getLast().get("id"));
        for (var identity : identities) {
            assertEquals(hash, identity.get("password_hash"));
            assertEquals("SUSPENDED", identity.get("status"));
            assertEquals(email, identity.get("email"));
        }
    }

    @Test
    void shouldPreserveExistingIdentityAndAuthorizedMembership() {
        String existingHash = migrator.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, fixture.userId);
        inTenant(fixture.tenantA, jdbc -> jdbc.update("UPDATE app_users SET email = ?, password_hash = ? WHERE tenant_id = ?",
                fixture.email, existingHash, fixture.tenantA));
        var result = backfill();
        assertEquals(1L, result.get("created_identities"));
        assertEquals(1L, result.get("created_memberships"));
        assertEquals("ACTIVE", migrator.queryForObject("SELECT status FROM users WHERE id = ?", String.class, fixture.userId));
        assertEquals(existingHash, migrator.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, fixture.userId));
        inTenant(fixture.tenantA, jdbc -> assertEquals(Map.of("role", "OWNER", "status", "ACTIVE"),
                jdbc.queryForMap("SELECT role, status FROM tenant_memberships WHERE user_id = ? AND tenant_id = ?",
                        fixture.userId, fixture.tenantA)));
    }

    @Test
    void shouldBeIdempotentIncludingRepeatedCallsInOneTransaction() {
        transaction.executeWithoutResult(status -> {
            assertEquals(2L, backfill().get("migrated_records"));
            assertEquals(Map.of("migrated_records", 0L, "created_identities", 0L,
                    "created_memberships", 0L, "reset_required", 0L), backfill());
        });
        assertEquals(2, migratedIdentities().size());
        assertNoTemporaryPolicies();
    }

    @Test
    void shouldRefuseConflictingCredentialsWithoutPartialChanges() {
        String email = UUID.randomUUID() + "@example.invalid";
        inTenant(fixture.tenantA, jdbc -> jdbc.update("UPDATE app_users SET email = ?, password_hash = 'hash-a' WHERE tenant_id = ?",
                email, fixture.tenantA));
        inTenant(fixture.tenantB, jdbc -> jdbc.update("UPDATE app_users SET email = ?, password_hash = 'hash-b' WHERE tenant_id = ?",
                email, fixture.tenantB));
        assertThrows(RuntimeException.class, this::backfill);
        assertTrue(migratedIdentities().isEmpty());
        assertEquals(0, migrator.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email));
        assertNoTemporaryPolicies();
    }

    @Test
    void shouldRefuseLinkingToExistingIdentityWithDifferentCredentials() {
        inTenant(fixture.tenantA, jdbc -> jdbc.update("UPDATE app_users SET email = ? WHERE tenant_id = ?",
                fixture.email, fixture.tenantA));
        assertThrows(RuntimeException.class, this::backfill);
        assertTrue(migratedIdentities().isEmpty());
        assertEquals("ACTIVE", migrator.queryForObject("SELECT status FROM users WHERE id = ?", String.class, fixture.userId));
        assertNoTemporaryPolicies();
    }

    @Test
    void shouldRefuseUuidCollisionAndKeepOriginalIdentity() {
        inTenant(fixture.tenantA, jdbc -> jdbc.update("UPDATE app_users SET id = ? WHERE tenant_id = ?", fixture.userId, fixture.tenantA));
        assertThrows(RuntimeException.class, this::backfill);
        assertTrue(migratedIdentities().isEmpty());
        assertEquals(fixture.email, migrator.queryForObject("SELECT email FROM users WHERE id = ?", String.class, fixture.userId));
        assertNoTemporaryPolicies();
    }

    @Test
    void shouldDenyMaintenanceFunctionAndAuditMappingToRuntime() {
        assertFalse(runtime.queryForObject("""
                SELECT has_function_privilege(current_user, 'public.backfill_legacy_app_users()', 'EXECUTE')
                """, Boolean.class));
        assertThrows(RuntimeException.class, () -> runtime.queryForMap("SELECT * FROM public.backfill_legacy_app_users()"));
        assertThrows(RuntimeException.class, () -> runtime.queryForObject("SELECT COUNT(*) FROM legacy_user_identity_migrations", Integer.class));
    }
}
