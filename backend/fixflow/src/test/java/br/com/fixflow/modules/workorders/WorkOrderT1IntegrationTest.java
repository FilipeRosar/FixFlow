package br.com.fixflow.modules.workorders;

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
class WorkOrderT1IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    MembershipFixtures fixture;
    JdbcTemplate admin;
    TransactionTemplate transaction;
    String token;
    final String url = "/api/v1/work-orders";
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
            admin.update("DELETE FROM outbox_event WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM work_orders WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM customers WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        });
        fixture.close();
    }
    UUID customer(UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, ?)", id, tenant, name));
        return id;
    }
    UUID equipment(UUID tenant, UUID customer) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> admin.update("INSERT INTO equipment(id, tenant_id, customer_id, category, model) VALUES (?, ?, ?, 'NOTEBOOK', 'Dell')", id, tenant, customer));
        return id;
    }
    Map<String, Object> body(UUID customer, UUID equipment) {
        return Map.of("customerId", customer, "equipmentId", equipment, "reportedIssue", "Não liga",
                "receivedAccessories", List.of("Carregador"), "physicalCondition", "Risco na tampa");
    }
    UUID open(UUID customer, UUID equipment, long number) throws Exception {
        var result = mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body(customer, equipment))))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.number").value(number)).andExpect(jsonPath("$.status").value("RECEBIDA"))
                .andExpect(jsonPath("$.receivedAccessories[0]").value("Carregador"))
                .andExpect(jsonPath("$.version").value(0)).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
    @Test void t1CreatesSequentialOrdersWithHistoryAuditAndEventPerTenant() throws Exception {
        UUID customer = customer(fixture.tenantA, "Ana");
        UUID equipment = equipment(fixture.tenantA, customer);
        UUID id = open(customer, equipment, 1);
        open(customer, equipment, 2);
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reportedIssue").value("Não liga"))
                .andExpect(jsonPath("$.customerName").value("Ana"))
                .andExpect(jsonPath("$.equipmentLabel").value("Dell"));
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ? AND from_status IS NULL AND to_status = 'RECEBIDA' AND changed_by = ?", Integer.class, id, fixture.userId));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_CREATED'", Integer.class, id));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'WorkOrderCreated'", Integer.class, id));
        });
        fixture.activateSecondMembership();
        var response = mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request", "true").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", fixture.email, "password", fixture.password, "tenantId", fixture.tenantB))))
                .andExpect(status().isOk()).andReturn();
        token = mapper.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
        UUID other = customer(fixture.tenantB, "Bruno");
        open(other, equipment(fixture.tenantB, other), 1);
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }
    @Test void rejectsInvalidReferencesAndRollsBackQuotaAndNumber() throws Exception {
        UUID customer = customer(fixture.tenantA, "Ana");
        UUID equipment = equipment(fixture.tenantA, customer);
        UUID foreign = customer(fixture.tenantB, "Outro tenant");
        UUID other = customer(fixture.tenantA, "Outro cliente");
        for (var invalid : List.of(body(customer, UUID.randomUUID()), body(other, equipment), body(foreign, equipment(fixture.tenantB, foreign)))) {
            mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                    .content(mapper.writeValueAsString(invalid))).andExpect(status().isNotFound());
        }
        open(customer, equipment, 1);
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT used FROM tenant_order_usage WHERE tenant_id = ?", Integer.class, fixture.tenantA)));
    }
    @Test void enforcesValidationAndCreationPermission() throws Exception {
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        UUID customer = customer(fixture.tenantA, "Ana");
        UUID equipment = equipment(fixture.tenantA, customer);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE user_id = ? AND tenant_id = ?", fixture.userId, fixture.tenantA));
        token = login();
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body(customer, equipment)))).andExpect(status().isForbidden());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE user_id = ? AND tenant_id = ?", fixture.userId, fixture.tenantA));
        token = login();
        open(customer, equipment, 1);
    }
    @Test void serializesConcurrentOpeningsWithoutDuplicateNumbers() throws Exception {
        UUID customer = customer(fixture.tenantA, "Concorrência");
        UUID equipment = equipment(fixture.tenantA, customer);
        String content = mapper.writeValueAsString(body(customer, equipment));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                var result = mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json").content(content))
                        .andExpect(status().isCreated()).andReturn();
                return mapper.readTree(result.getResponse().getContentAsString()).get("number").asLong();
            }));
            var numbers = new TreeSet<Long>();
            for (var future : futures) numbers.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(new TreeSet<>(List.of(1L, 2L, 3L, 4L, 5L, 6L)), numbers);
        }
    }
    @Test void rejectsDeletedEquipmentAndCustomerAndBlankDetails() throws Exception {
        UUID customer = customer(fixture.tenantA, "Cliente");
        UUID equipment = equipment(fixture.tenantA, customer);
        var invalid = new HashMap<>(body(customer, equipment));
        invalid.put("reportedIssue", "   ");
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE equipment SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", equipment));
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body(customer, equipment)))).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE customers SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", customer));
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body(customer, equipment)))).andExpect(status().isNotFound());
    }

}
