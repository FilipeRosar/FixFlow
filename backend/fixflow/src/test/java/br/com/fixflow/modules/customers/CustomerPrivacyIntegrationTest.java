package br.com.fixflow.modules.customers;

import br.com.fixflow.support.MembershipFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class CustomerPrivacyIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    br.com.fixflow.shared.application.audit.AuditService audit;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    MembershipFixtures fixture;
    JdbcTemplate admin;
    TransactionTemplate transaction;
    String token;
    final String url = "/api/v1/customers";
    @BeforeEach void setup() throws Exception {
        fixture = new MembershipFixtures(environment, encoder);
        var ds = new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
        admin = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        token = login();
    }
    String login() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request", "true").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", fixture.email, "password", fixture.password))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
    void inTenant(UUID tenant, Runnable action) {
        transaction.executeWithoutResult(tx -> {
            admin.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            action.run();
        });
    }
    @AfterEach void cleanup() {
        for (var tenant : List.of(fixture.tenantA, fixture.tenantB)) inTenant(tenant, () -> {
            admin.update("DELETE FROM work_orders WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM customers WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        });
        fixture.close();
    }
    UUID customer(UUID tenant) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> {
            admin.update("INSERT INTO customers(id, tenant_id, name, document) VALUES (?, ?, 'Ana Silva', '12345678900')", id, tenant);
            admin.update("INSERT INTO customer_contacts(tenant_id, customer_id, type, value, label, position) VALUES (?, ?, 'EMAIL', 'ana@example.invalid', 'Pessoal', 0)", tenant, id);
            admin.update("INSERT INTO equipment(tenant_id, customer_id, category, brand, model, serial_number, description) VALUES (?, ?, 'CELULAR', 'Marca', 'Modelo Ana', 'ANA-SERIAL', 'Pertence a Ana')", tenant, id);
        });
        return id;
    }
    UUID order(UUID customer, String status) {
        UUID id = UUID.randomUUID();
        inTenant(fixture.tenantA, () -> admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, status) VALUES (?, ?, ?, ?)", id, fixture.tenantA, customer, status));
        return id;
    }
    void anonymize(UUID customer, int expected) throws Exception {
        mvc.perform(post(url + "/" + customer + "/anonymize").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("confirmed", true))))
                .andExpect(status().is(expected));
    }
    @Test void exportIncludesEveryCurrentRecordAndIsAudited() throws Exception {
        UUID customer = customer(fixture.tenantA);
        UUID other = customer(fixture.tenantA);
        order(customer, "ENTREGUE"); order(customer, "RECEBIDA");
        inTenant(fixture.tenantA, () -> admin.update("UPDATE equipment SET deleted_at = CURRENT_TIMESTAMP WHERE customer_id = ?", customer));
        mvc.perform(get(url + "/" + customer + "/export").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=cliente-" + customer + ".json"))
                .andExpect(jsonPath("$.customer.id").value(customer.toString())).andExpect(jsonPath("$.customer.name").value("Ana Silva"))
                .andExpect(jsonPath("$.customer.contacts[0].value").value("ana@example.invalid"))
                .andExpect(jsonPath("$.equipment.length()").value(1)).andExpect(jsonPath("$.equipment[0].serialNumber").value("ANA-SERIAL"))
                .andExpect(jsonPath("$.workOrders.length()").value(2));
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND actor_id = ? AND action = 'CUSTOMER_EXPORTED' AND details = '{}'::jsonb", Integer.class, customer, fixture.userId));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ?", Integer.class, other));
        });
    }
    @Test void anonymizesAtomicallyPreservesOrdersAndPreventsReidentification() throws Exception {
        UUID customer = customer(fixture.tenantA);
        UUID delivered = order(customer, "ENTREGUE"); UUID canceled = order(customer, "CANCELADA");
        var before = new java.util.concurrent.atomic.AtomicReference<List<Map<String, Object>>>();
        inTenant(fixture.tenantA, () -> before.set(admin.queryForList("SELECT * FROM work_orders WHERE customer_id = ? ORDER BY id", customer)));
        anonymize(customer, 204); anonymize(customer, 204);
        inTenant(fixture.tenantA, () -> {
            assertEquals(before.get(), admin.queryForList("SELECT * FROM work_orders WHERE customer_id = ? ORDER BY id", customer));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM customer_contacts WHERE customer_id = ?", Integer.class, customer));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND actor_id = ? AND action = 'CUSTOMER_ANONYMIZED' AND details = '{}'::jsonb", Integer.class, customer, fixture.userId));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM equipment WHERE customer_id = ? AND model = 'Equipamento anonimizado' AND serial_number = '' AND description = '' AND brand = '' AND deleted_at IS NOT NULL", Integer.class, customer));
        });
        mvc.perform(get(url + "/" + customer + "/export").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customer.name").value("Cliente anonimizado"))
                .andExpect(jsonPath("$.customer.document").isEmpty()).andExpect(jsonPath("$.customer.contacts.length()").value(0))
                .andExpect(jsonPath("$.customer.anonymizedAt").isNotEmpty()).andExpect(jsonPath("$.workOrders.length()").value(2));
        mvc.perform(get(url + "/" + customer + "/history").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.anonymized").value(true)).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get(url + "/" + customer).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> inTenant(fixture.tenantA,
                () -> admin.update("UPDATE customers SET name = 'Restaurado' WHERE id = ?", customer)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> inTenant(fixture.tenantA,
                () -> admin.update("INSERT INTO customer_contacts(tenant_id, customer_id, type, value, position) VALUES (?, ?, 'EMAIL', 'new@example.invalid', 0)", fixture.tenantA, customer)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> inTenant(fixture.tenantA,
                () -> admin.update("UPDATE equipment SET description = 'Identificável' WHERE customer_id = ?", customer)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> inTenant(fixture.tenantA,
                () -> admin.update("INSERT INTO work_orders(tenant_id, customer_id) VALUES (?, ?)", fixture.tenantA, customer)));
    }
    @Test void rejectsAllOpenOrderStatesWithoutChangingData() throws Exception {
        UUID customer = customer(fixture.tenantA); UUID order = order(customer, "RECEBIDA");
        for (String state : List.of("RECEBIDA", "EM_DIAGNOSTICO", "AGUARDANDO_ORCAMENTO", "AGUARDANDO_APROVACAO", "APROVADA", "ORCAMENTO_REJEITADO", "EM_MANUTENCAO", "AGUARDANDO_PECA", "PRONTA", "SEM_REPARO")) {
            inTenant(fixture.tenantA, () -> admin.update("UPDATE work_orders SET status = ? WHERE id = ?", state, order));
            anonymize(customer, 409);
        }
        inTenant(fixture.tenantA, () -> {
            assertEquals("Ana Silva", admin.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, customer));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM customer_contacts WHERE customer_id = ?", Integer.class, customer));
            assertEquals("ANA-SERIAL", admin.queryForObject("SELECT serial_number FROM equipment WHERE customer_id = ?", String.class, customer));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'CUSTOMER_ANONYMIZED'", Integer.class, customer));
        });
    }
    @Test void auditFailureRollsBackEveryAnonymizationChange() throws Exception {
        UUID customer = customer(fixture.tenantA); order(customer, "ENTREGUE");
        br.com.fixflow.shared.application.audit.AuditService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("Audit unavailable")).when(target)
                .record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CUSTOMER_ANONYMIZED"), org.mockito.ArgumentMatchers.eq("CUSTOMER"), org.mockito.ArgumentMatchers.eq(customer), org.mockito.ArgumentMatchers.anyString());
        anonymize(customer, 500);
        inTenant(fixture.tenantA, () -> {
            assertEquals("Ana Silva", admin.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, customer));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM customer_contacts WHERE customer_id = ?", Integer.class, customer));
            assertEquals("ANA-SERIAL", admin.queryForObject("SELECT serial_number FROM equipment WHERE customer_id = ?", String.class, customer));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM customers WHERE id = ? AND anonymized_at IS NULL AND deleted_at IS NULL", Integer.class, customer));
        });
    }
    @Test void ownerOnlyAndTenantIsolationAndConfirmation() throws Exception {
        UUID own = customer(fixture.tenantA); UUID foreign = customer(fixture.tenantB);
        for (var id : List.of(foreign, UUID.randomUUID())) {
            anonymize(id, 404);
            mvc.perform(get(url + "/" + id + "/export").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        }
        for (String body : List.of("{}", "{\"confirmed\":false}"))
            mvc.perform(post(url + "/" + own + "/anonymize").header("Authorization", "Bearer " + token).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for (String role : List.of("ATTENDANT", "TECHNICIAN")) {
            inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = ? WHERE user_id = ? AND tenant_id = ?", role, fixture.userId, fixture.tenantA));
            token = login(); anonymize(own, 403);
            mvc.perform(get(url + "/" + own + "/export").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
    }
    @Test void canExportAndAnonymizeSoftDeletedCustomers() throws Exception {
        UUID customer = customer(fixture.tenantA);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE customers SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", customer));
        mvc.perform(get(url + "/" + customer + "/export").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        anonymize(customer, 204);
    }
}
