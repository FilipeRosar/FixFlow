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
class WorkOrderQueueIntegrationTest {
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
    record QueueData(UUID mine, UUID other, UUID unassigned, UUID colleague) { }
    QueueData seed() throws Exception {
        UUID customer = customer(fixture.tenantA, "Ana"); UUID equipment = equipment(fixture.tenantA, customer);
        UUID mine = open(customer, equipment, 1), other = open(customer, equipment, 2), unassigned = open(customer, equipment, 3);
        UUID colleague = member(fixture.tenantA, "TECHNICIAN", "ACTIVE", "ACTIVE");
        assign(mine, fixture.userId, 0).andExpect(status().isOk()); assign(other, colleague, 0).andExpect(status().isOk());
        UUID foreignCustomer = customer(fixture.tenantB, "Outro tenant");
        inTenant(fixture.tenantB, () -> admin.update("INSERT INTO work_orders(tenant_id, customer_id) VALUES (?, ?)", fixture.tenantB, foreignCustomer));
        return new QueueData(mine, other, unassigned, colleague);
    }
    void role(String role) throws Exception {
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = ? WHERE user_id = ? AND tenant_id = ?", role, fixture.userId, fixture.tenantA)); token = login();
    }
    @Test void technicianQueueForcesOwnAssignmentAndRejectsForeignTechnicianFilter() throws Exception {
        var data = seed(); role("TECHNICIAN");
        mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(data.mine().toString()));
        mvc.perform(get(url).param("technicianId", fixture.userId.toString()).param("openOnly", "false").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        for (UUID foreign : List.of(data.colleague(), UUID.randomUUID()))
            mvc.perform(get(url).param("technicianId", foreign.toString()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        for (UUID denied : List.of(data.other(), data.unassigned()))
            mvc.perform(get(url + "/" + denied).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void ownerAndAttendantCanFilterAndPageOnlyWithinTheirTenant() throws Exception {
        var data = seed();
        for (String role : List.of("OWNER", "ATTENDANT")) {
            role(role);
            mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
            mvc.perform(get(url).param("technicianId", data.colleague().toString()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(data.other().toString()));
            mvc.perform(get(url).param("size", "1").param("page", "1").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.page").value(1));
        }
    }
    @Test void reassigningImmediatelyRemovesTheOrderFromPreviousTechnicianQueueAndDetail() throws Exception {
        var data = seed(); assign(data.mine(), data.colleague(), 1).andExpect(status().isOk()); role("TECHNICIAN");
        mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(url + "/" + data.mine()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void closedAndStatusFiltersNeverBroadenTechnicianScopeAndInvalidRequestsAreRejected() throws Exception {
        var data = seed();
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_orders SET status = 'CANCELADA' WHERE id = ?", data.mine()));
        role("TECHNICIAN");
        mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(url).param("openOnly", "false").param("status", "CANCELADA").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(data.mine().toString()));
        for (var params : List.of(Map.of("page", "-1"), Map.of("size", "101"), Map.of("status", "UNKNOWN"))) {
            var request = get(url).header("Authorization", "Bearer " + token); params.forEach(request::param); mvc.perform(request).andExpect(status().isBadRequest());
        }
        role("VIEWER"); mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }
}
