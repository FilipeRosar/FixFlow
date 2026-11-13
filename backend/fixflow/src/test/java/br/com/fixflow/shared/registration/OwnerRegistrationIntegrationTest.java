package br.com.fixflow.shared.registration;

import br.com.fixflow.shared.application.registration.usecase.AccountRegistration;
import br.com.fixflow.shared.application.registration.dto.RegistrationAddress;
import br.com.fixflow.shared.application.registration.EmailAlreadyRegisteredException;
import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class OwnerRegistrationIntegrationTest {
    private static final String PASSWORD = "Owner12!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired AccountRegistration registration;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate runtime;

    @Test
    void originRollbackRemovesIdentityTenantAndMembership() {
        String company = UUID.randomUUID().toString();
        String email = company + "@example.invalid";
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            registration.register(company, "Owner", "Test", email, PASSWORD, "52998224725", address(), "11999999999");
            assertEquals("", runtime.queryForObject("SELECT coalesce(current_setting('app.tenant_id', true), '')", String.class));
            status.setRollbackOnly();
        });
        assertEquals(0, owner().queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email));
        assertEquals(0, countTenants(company));
    }

    @Test
    void concurrentSameEmailCreatesExactlyOneCompany() throws Exception {
        String company = UUID.randomUUID().toString();
        String email = company + "@example.invalid";
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> task = () -> {
                try {
                    registration.register(company, "Owner", "Test", email, PASSWORD, "52998224725", address(), "11999999999");
                    return true;
                } catch (EmailAlreadyRegisteredException exception) { return false; }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, countTenants(company));
        } finally { cleanup(email); }
    }

    private RegistrationAddress address() {
        return new RegistrationAddress("Rua Teste", "123", "", "Centro", "São Paulo", "SP", "01001000");
    }

    private String body(String company, String email, String password) {
        return mapper.writeValueAsString(java.util.Map.of("companyName", company, "firstName", "Owner", "lastName", "Test",
                "email", email, "password", password, "document", "52998224725", "address", address(), "phone", "11999999999"));
    }

    private DriverManagerDataSource ownerSource() {
        String url = environment.getRequiredProperty("spring.flyway.url");
        if (!url.matches("jdbc:postgresql://[^/]+/fixflow_test(?:\\?.*)?")) throw new IllegalStateException("Test database required");
        return new DriverManagerDataSource(url, environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
    }

    private JdbcTemplate owner() { return new JdbcTemplate(ownerSource()); }

    private int countTenants(String company) {
        UUID.fromString(company); // Only generated UUIDs are interpolated into fixture policies.
        var source = ownerSource();
        var jdbc = new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
            jdbc.execute("CREATE POLICY registration_test_count ON tenants FOR SELECT TO fixflow_migrator USING (name = '" + company + "')");
            int count = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE name = ?", Integer.class, company);
            jdbc.execute("DROP POLICY registration_test_count ON tenants");
            return count;
        });
    }

    private void cleanup(String email) {
        var source = ownerSource();
        var jdbc = new JdbcTemplate(source);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            var users = jdbc.queryForList("SELECT id FROM users WHERE email = ?", UUID.class, email);
            for (UUID user : users) {
                jdbc.execute("CREATE POLICY registration_test_cleanup ON tenant_memberships FOR ALL TO fixflow_migrator USING (user_id = '" + user + "'::uuid)");
                var tenants = jdbc.queryForList("SELECT tenant_id FROM tenant_memberships WHERE user_id = ?", UUID.class, user);
                jdbc.update("DELETE FROM tenant_memberships WHERE user_id = ?", user);
                for (UUID tenant : tenants) {
                    jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
                    jdbc.update("DELETE FROM outbox_event WHERE tenant_id = ?", tenant);
                    jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
                    jdbc.update("DELETE FROM tenants WHERE id = ?", tenant);
                }
                jdbc.update("DELETE FROM users WHERE id = ?", user);
                jdbc.execute("DROP POLICY registration_test_cleanup ON tenant_memberships");
            }
        });
    }
}
