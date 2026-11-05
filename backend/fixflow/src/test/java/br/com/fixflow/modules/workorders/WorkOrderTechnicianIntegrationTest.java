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
class WorkOrderTechnicianIntegrationTest {
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
    final List<UUID> extraUsers = new ArrayList<>();
    @AfterEach void cleanup() {
        for (var tenant : List.of(fixture.tenantA, fixture.tenantB)) inTenant(tenant, () -> {
            admin.update("DELETE FROM outbox_event WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM work_orders WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM customers WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        });
        fixture.close();
        for (UUID user : extraUsers) admin.update("DELETE FROM users WHERE id = ?", user);
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
    UUID order() throws Exception {
        UUID customer = customer(fixture.tenantA, "Ana");
        return open(customer, equipment(fixture.tenantA, customer), 1);
    }
    UUID member(UUID tenant, String role, String membershipStatus, String userStatus) {
        UUID id = UUID.randomUUID(); extraUsers.add(id);
        admin.update("INSERT INTO users(id, full_name, email, password_hash, status) VALUES (?, 'Técnico de teste', ?, 'unused-hash', ?)", id, id + "@example.invalid", userStatus);
        inTenant(tenant, () -> admin.update("INSERT INTO tenant_memberships(user_id, tenant_id, role, status) VALUES (?, ?, ?, ?)", id, tenant, role, membershipStatus));
        return id;
    }
    org.springframework.test.web.servlet.ResultActions assign(UUID order, UUID technician, int version) throws Exception {
        return mvc.perform(patch(url + "/" + order + "/technician").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("technicianId", technician, "version", version))));
    }
    @Test void listsOnlyActiveTenantCandidatesAndAssignmentUnlocksDiagnosis() throws Exception {
        UUID id = order();
        mvc.perform(get(url + "/technicians").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(fixture.userId.toString())).andExpect(jsonPath("$[0].email").doesNotExist());
        assign(id, fixture.userId, 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.technicianId").value(fixture.userId.toString())).andExpect(jsonPath("$.technicianName").value("Integration identity"));
        mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("toStatus", "EM_DIAGNOSTICO", "version", 1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EM_DIAGNOSTICO"));
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_TECHNICIAN_ASSIGNED'", Integer.class, id));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'WorkOrderTechnicianAssigned'", Integer.class, id));
        });
    }
    @Test void rejectsInvalidForeignSuspendedAndNonTechnicalCandidates() throws Exception {
        UUID id = order();
        UUID foreign = member(fixture.tenantB, "TECHNICIAN", "ACTIVE", "ACTIVE");
        UUID invalid = member(fixture.tenantA, "ATTENDANT", "ACTIVE", "ACTIVE");
        for (UUID candidate : List.of(UUID.randomUUID(), foreign, invalid)) assign(id, candidate, 0).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN', status = 'SUSPENDED' WHERE user_id = ?", invalid));
        assign(id, invalid, 0).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET status = 'ACTIVE' WHERE user_id = ?", invalid));
        admin.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", invalid);
        assign(id, invalid, 0).andExpect(status().isNotFound());
        mvc.perform(get(url + "/technicians").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
    @Test void attendantCanAssignButTechnicianCannotAndStaleVersionsAreRejected() throws Exception {
        UUID id = order(); UUID technician = member(fixture.tenantA, "TECHNICIAN", "ACTIVE", "ACTIVE");
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE user_id = ?", fixture.userId)); token = login();
        mvc.perform(get(url + "/technicians").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assign(id, technician, 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assign(id, technician, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_VERSION_CONFLICT"));
        assign(id, technician, 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE user_id = ?", fixture.userId)); token = login();
        assign(id, fixture.userId, 1).andExpect(status().isForbidden());
        mvc.perform(get(url + "/technicians").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void rejectsForeignOrdersAndClosedOrdersAndPreservesExistingFacts() throws Exception {
        UUID id = order();
        assign(UUID.randomUUID(), fixture.userId, 0).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("INSERT INTO work_order_transition_facts(tenant_id, work_order_id, diagnosis) VALUES (?, ?, 'Diagnóstico existente')", fixture.tenantA, id));
        assign(id, fixture.userId, 0).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> {
            assertEquals("Diagnóstico existente", admin.queryForObject("SELECT diagnosis FROM work_order_transition_facts WHERE work_order_id = ?", String.class, id));
            admin.update("UPDATE work_orders SET status = 'CANCELADA' WHERE id = ?", id);
        });
        assign(id, fixture.userId, 1).andExpect(status().isConflict());
    }
}
