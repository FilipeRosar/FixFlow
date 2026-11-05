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
class WorkOrderDiagnosisIntegrationTest {
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
    UUID diagnosingOrder() throws Exception {
        UUID id = order(); assign(id, fixture.userId, 0).andExpect(status().isOk());
        transition(id, "EM_DIAGNOSTICO", 1).andExpect(status().isOk()); return id;
    }
    org.springframework.test.web.servlet.ResultActions transition(UUID id, String target, int version) throws Exception {
        return mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(Map.of("toStatus", target, "version", version))));
    }
    org.springframework.test.web.servlet.ResultActions record(UUID id, int version, String findings) throws Exception {
        return mvc.perform(post(url + "/" + id + "/diagnosis").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("version", version, "findings", findings, "probableCause", "Fonte danificada", "proposedSolution", "Trocar fonte"))));
    }
    void role(String role) throws Exception {
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = ? WHERE tenant_id = ? AND user_id = ?", role, fixture.tenantA, fixture.userId)); token = login();
    }
    @Test void recordsAndRevisesDiagnosisWithImmutableHistoryAndUnlocksQuoteTransition() throws Exception {
        UUID id = diagnosingOrder();
        transition(id, "AGUARDANDO_ORCAMENTO", 2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        record(id, 2, "  Não liga  ").andExpect(status().isCreated()).andExpect(jsonPath("$.workOrderVersion").value(3))
                .andExpect(jsonPath("$.diagnosis.revision").value(1)).andExpect(jsonPath("$.diagnosis.findings").value("Não liga"));
        record(id, 3, "Fonte em curto").andExpect(status().isCreated()).andExpect(jsonPath("$.workOrderVersion").value(4)).andExpect(jsonPath("$.diagnosis.revision").value(2));
        mvc.perform(get(url + "/" + id + "/diagnosis").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].findings").value("Fonte em curto"))
                .andExpect(jsonPath("$[1].findings").value("Não liga")).andExpect(jsonPath("$[0].recordedBy").value(fixture.userId.toString()))
                .andExpect(jsonPath("$[0].createdAt").exists());
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.diagnosis.revision").value(2));
        transition(id, "AGUARDANDO_ORCAMENTO", 4).andExpect(status().isOk());
        record(id, 5, "Alteração tardia").andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> {
            assertEquals(2, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_DIAGNOSIS_RECORDED'", Integer.class, id));
            assertEquals(2, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'DiagnosisRecorded'", Integer.class, id));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'DiagnosisCompleted'", Integer.class, id));
        });
    }
    @Test void enforcesAssignedTechnicianAndAttendantReadOnlyAccess() throws Exception {
        UUID id = diagnosingOrder(); record(id, 2, "Achados").andExpect(status().isCreated());
        role("ATTENDANT");
        mvc.perform(get(url + "/" + id + "/diagnosis").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        record(id, 3, "Não autorizado").andExpect(status().isForbidden());
        role("TECHNICIAN"); record(id, 3, "Revisão pelo técnico").andExpect(status().isCreated());
        UUID colleague = member(fixture.tenantA, "TECHNICIAN", "ACTIVE", "ACTIVE");
        role("OWNER"); assign(id, colleague, 4).andExpect(status().isOk()); role("TECHNICIAN");
        record(id, 5, "Sem atribuição").andExpect(status().isForbidden());
        mvc.perform(get(url + "/" + id + "/diagnosis").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void validatesAllFieldsAndDoesNotTrustTheLegacyProjectionString() throws Exception {
        UUID id = diagnosingOrder();
        for (String invalid : List.of(" ", "x".repeat(4001))) record(id, 2, invalid).andExpect(status().isBadRequest());
        mvc.perform(post(url + "/" + id + "/diagnosis").header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_transition_facts SET diagnosis = 'flag antiga não é um diagnóstico' WHERE work_order_id = ?", id));
        transition(id, "AGUARDANDO_ORCAMENTO", 2).andExpect(status().isConflict());
        mvc.perform(get(url + "/" + id + "/diagnosis").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void validatesStateVersionTenantAndConcurrentRevisions() throws Exception {
        UUID id = order(); record(id, 0, "Antes do diagnóstico").andExpect(status().isConflict());
        assign(id, fixture.userId, 0).andExpect(status().isOk()); transition(id, "EM_DIAGNOSTICO", 1).andExpect(status().isOk());
        record(UUID.randomUUID(), 2, "Ausente").andExpect(status().isNotFound());
        UUID customer = customer(fixture.tenantB, "Outro tenant"); UUID foreign = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> admin.update("INSERT INTO work_orders(id, tenant_id, customer_id) VALUES (?, ?, ?)", foreign, fixture.tenantB, customer));
        record(foreign, 0, "Outra empresa").andExpect(status().isNotFound());
        mvc.perform(get(url + "/" + foreign + "/diagnosis").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> record(id, 2, "Concorrente").andReturn().getResponse().getStatus()));
            var statuses = new ArrayList<Integer>(); for (var future : futures) statuses.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            Collections.sort(statuses); assertEquals(List.of(201, 409), statuses);
        }
        record(id, 2, "Versão antiga").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_VERSION_CONFLICT"));
    }
    @Test void outboxFailureRollsBackDiagnosisAndVersionAndRuntimeCannotRewriteHistory() throws Exception {
        UUID id = diagnosingOrder();
        assertFalse(admin.queryForObject("SELECT has_table_privilege('fixflow_runtime', 'public.work_order_diagnoses', 'UPDATE') OR has_table_privilege('fixflow_runtime', 'public.work_order_diagnoses', 'DELETE')", Boolean.class));
        String name = "fail_diagnosis_" + id.toString().replace("-", "");
        admin.execute("CREATE FUNCTION public." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Forced test failure'; END; $$");
        try {
            admin.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON public.outbox_event FOR EACH ROW WHEN (NEW.aggregate_id = '" + id + "'::uuid) EXECUTE FUNCTION public." + name + "()");
            try {
                record(id, 2, "Falha simulada").andExpect(status().isInternalServerError());
                inTenant(fixture.tenantA, () -> {
                    assertEquals(2, admin.queryForObject("SELECT version FROM work_orders WHERE id = ?", Integer.class, id));
                    assertEquals(0, admin.queryForObject("SELECT count(*) FROM work_order_diagnoses WHERE work_order_id = ?", Integer.class, id));
                    assertEquals(0, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_DIAGNOSIS_RECORDED'", Integer.class, id));
                });
            } finally { admin.execute("DROP TRIGGER " + name + " ON public.outbox_event"); }
        } finally { admin.execute("DROP FUNCTION public." + name + "()"); }
    }

}
