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
class CustomerHistoryIntegrationTest {
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
        inTenant(tenant, () -> admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, 'Cliente histórico')", id, tenant));
        return id;
    }
    UUID equipment(UUID tenant, UUID customer, String created, boolean deleted) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> admin.update("INSERT INTO equipment(id, tenant_id, customer_id, category, model, serial_number, created_at, deleted_at) VALUES (?, ?, ?, 'CELULAR', 'Galaxy', 'SERIAL', ?::timestamptz, ?::timestamptz)",
                id, tenant, customer, created, deleted ? created : null));
        return id;
    }
    UUID order(UUID tenant, UUID customer, String created, String status) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, status, created_at) VALUES (?, ?, ?, ?, ?::timestamptz)", id, tenant, customer, status, created));
        return id;
    }
    String history(UUID customer) { return url + "/" + customer + "/history"; }
    @Test void mixesOrdersAndEquipmentChronologicallyAcrossPages() throws Exception {
        UUID customer = customer(fixture.tenantA);
        UUID oldest = equipment(fixture.tenantA, customer, "2025-01-01T12:00:00Z", false);
        UUID closed = order(fixture.tenantA, customer, "2025-02-01T12:00:00Z", "ENTREGUE");
        UUID removed = equipment(fixture.tenantA, customer, "2025-03-01T12:00:00Z", true);
        UUID open = order(fixture.tenantA, customer, "2025-04-01T12:00:00Z", "RECEBIDA");
        UUID latest = equipment(fixture.tenantA, customer, "2025-05-01T12:00:00Z", false);
        mvc.perform(get(history(customer)).param("size", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerName").value("Cliente histórico"))
                .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.workOrdersVisible").value(true))
                .andExpect(jsonPath("$.content[0].id").value(latest.toString())).andExpect(jsonPath("$.content[0].type").value("EQUIPMENT"))
                .andExpect(jsonPath("$.content[1].id").value(open.toString()));
        mvc.perform(get(history(customer)).param("size", "2").param("page", "1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(removed.toString()))
                .andExpect(jsonPath("$.content[0].deleted").value(true)).andExpect(jsonPath("$.content[1].id").value(closed.toString()));
        mvc.perform(get(history(customer)).param("size", "2").param("page", "2").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value(oldest.toString()));
        mvc.perform(get(history(customer)).param("sort", "asc").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.content[0].id").value(oldest.toString())).andExpect(jsonPath("$.content[4].id").value(latest.toString()));
        mvc.perform(get(history(customer)).param("type", "EQUIPMENT").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get(history(customer)).param("type", "WORK_ORDER").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[0].status").value("RECEBIDA"));
    }
    @Test void resolvesDateTiesDeterministicallyAndReturnsEmptyPages() throws Exception {
        UUID customer = customer(fixture.tenantA);
        UUID first = equipment(fixture.tenantA, customer, "2025-01-01T00:00:00Z", false);
        UUID second = equipment(fixture.tenantA, customer, "2025-01-01T00:00:00Z", false);
        UUID order = order(fixture.tenantA, customer, "2025-01-01T00:00:00Z", "CANCELADA");
        var seen = new java.util.HashSet<String>();
        for (int page = 0; page < 3; page++) {
            var result = mvc.perform(get(history(customer)).param("size", "1").param("page", String.valueOf(page)).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3)).andReturn();
            var row = mapper.readTree(result.getResponse().getContentAsString()).get("content").get(0);
            assertTrue(seen.add(row.get("id").asText()));
            if (page == 0) assertEquals(order.toString(), row.get("id").asText());
        }
        assertEquals(Set.of(first.toString(), second.toString(), order.toString()), seen);
        mvc.perform(get(history(customer)).param("page", "10").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0)).andExpect(jsonPath("$.totalElements").value(3));
    }
    @Test void isolatesTenantsAndCustomersAndRejectsUnavailableCustomers() throws Exception {
        UUID own = customer(fixture.tenantA);
        UUID other = customer(fixture.tenantA);
        UUID foreign = customer(fixture.tenantB);
        equipment(fixture.tenantA, other, "2025-01-01T00:00:00Z", false);
        order(fixture.tenantB, foreign, "2025-01-01T00:00:00Z", "RECEBIDA");
        mvc.perform(get(history(own)).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        for (var id : List.of(foreign, UUID.randomUUID()))
            mvc.perform(get(history(id)).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE customers SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", own));
        mvc.perform(get(history(own)).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }
    @Test void technicianSeesEquipmentButNoOrderDataOrCounts() throws Exception {
        UUID customer = customer(fixture.tenantA);
        equipment(fixture.tenantA, customer, "2025-01-01T00:00:00Z", false);
        order(fixture.tenantA, customer, "2025-02-01T00:00:00Z", "RECEBIDA");
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE user_id = ? AND tenant_id = ?", fixture.userId, fixture.tenantA));
        token = login();
        mvc.perform(get(history(customer)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workOrdersVisible").value(false))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].type").value("EQUIPMENT"));
        mvc.perform(get(history(customer)).param("type", "WORK_ORDER").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void validatesFiltersAndRequiresAuthentication() throws Exception {
        UUID customer = customer(fixture.tenantA);
        for (var entry : Map.of("type", "INVALID", "sort", "INVALID", "page", "-1", "size", "101").entrySet())
            mvc.perform(get(history(customer)).param(entry.getKey(), entry.getValue()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        mvc.perform(get(history(customer))).andExpect(status().isUnauthorized());
    }
}
