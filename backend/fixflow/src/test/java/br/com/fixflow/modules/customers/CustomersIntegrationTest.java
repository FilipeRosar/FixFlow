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
class CustomersIntegrationTest {
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
    Map<String, Object> body(String name) {
        return Map.of("name", name, "document", "123.456.789-00", "contacts", List.of(
                Map.of("type", "WHATSAPP", "value", "(11) 98765-4321", "label", "Principal"),
                Map.of("type", "EMAIL", "value", "cliente@example.com", "label", "Trabalho")));
    }
    UUID create(String name) throws Exception {
        var result = mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body(name))))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.contacts.length()").value(2)).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
    @Test void crudSearchPaginationAndSoftDelete() throws Exception {
        UUID id = create("Ana Silva");
        create("Bruno");
        for (String q : List.of("ANA", "11987654321", "(11) 98765", "123.456.789-00"))
            mvc.perform(get(url).param("q", q).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(id.toString()));
        mvc.perform(get(url).param("size", "1").param("page", "1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Bruno"));
        mvc.perform(put(url + "/" + id).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("name", "Ana atualizada", "contacts", List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contacts.length()").value(0));
        mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(put(url + "/" + id).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body("Reativado")))).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM customers WHERE id = ? AND deleted_at IS NOT NULL", Integer.class, id)));
        mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.totalElements").value(1));
    }
    @Test void blocksDeletionForEveryNonterminalOrderAndAllowsClosedOrders() throws Exception {
        UUID id = create("Cliente com OS");
        UUID order = UUID.randomUUID();
        inTenant(fixture.tenantA, () -> admin.update("INSERT INTO work_orders(id, tenant_id, customer_id) VALUES (?, ?, ?)", order, fixture.tenantA, id));
        for (String status : List.of("RECEBIDA", "PRONTA", "SEM_REPARO", "ORCAMENTO_REJEITADO")) {
            inTenant(fixture.tenantA, () -> admin.update("UPDATE work_orders SET status = ? WHERE id = ?", status, order));
            mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
        }
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_orders SET status = 'ENTREGUE' WHERE id = ?", order));
        mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> inTenant(fixture.tenantA,
                () -> admin.update("INSERT INTO work_orders(tenant_id, customer_id) VALUES (?, ?)", fixture.tenantA, id)));
    }
    @Test void enforcesTenantIsolationAndTechnicianReadOnly() throws Exception {
        UUID foreign = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, 'Outro tenant')", foreign, fixture.tenantB));
        mvc.perform(get(url + "/" + foreign).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(delete(url + "/" + foreign).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        UUID id = create("Cliente local");
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE user_id = ? AND tenant_id = ?", fixture.userId, fixture.tenantA));
        token = login();
        mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body("Negado")))).andExpect(status().isForbidden());
        mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void validatesInputAndRollsBackInvalidContactUpdates() throws Exception {
        mvc.perform(get(url).header("Authorization", "Bearer " + token).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(url).header("Authorization", "Bearer " + token).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("name", " ", "contacts", List.of())))).andExpect(status().isBadRequest());
        UUID id = create("Original");
        mvc.perform(put(url + "/" + id).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("name", "Alterado", "contacts", List.of(Map.of("type", "EMAIL", "value", "invalido"))))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.name").value("Original")).andExpect(jsonPath("$.contacts.length()").value(2));
    }
}
