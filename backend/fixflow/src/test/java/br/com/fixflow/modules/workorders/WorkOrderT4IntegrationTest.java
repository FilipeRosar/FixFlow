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
class WorkOrderT4IntegrationTest {
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
    UUID order() throws Exception {
        UUID customer = customer(fixture.tenantA, "Ana");
        return open(customer, equipment(fixture.tenantA, customer), 1);
    }
    void evidence(UUID id) {
        inTenant(fixture.tenantA, () -> admin.update("""
                INSERT INTO work_order_transition_facts(tenant_id, work_order_id, technician_id, diagnosis,
                    quote_id, quote_item_count, quote_total, quote_valid_until, service_count,
                    payment_settled, diagnostic_fee_registered, missing_part, warranty_days)
                VALUES (?, ?, ?, 'Diagnóstico preenchido', ?, 1, 100, CURRENT_TIMESTAMP + interval '1 day', 1, true, true, 'Fonte', 90)
                """, fixture.tenantA, id, fixture.userId, UUID.randomUUID()));
    }
    org.springframework.test.web.servlet.ResultActions transition(UUID id, String target, int version, String reason) throws Exception {
        var body = new HashMap<String, Object>(); body.put("toStatus", target); body.put("version", version); body.put("reason", reason);
        return mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(mapper.writeValueAsString(body)));
    }
    @Test void t4RejectsReceivedToMaintenanceAndLeavesAllRecordsUnchanged() throws Exception {
        UUID id = order();
        transition(id, "EM_MANUTENCAO", 0, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        inTenant(fixture.tenantA, () -> {
            assertEquals("RECEBIDA", admin.queryForObject("SELECT status FROM work_orders WHERE id = ?", String.class, id));
            assertEquals(0, admin.queryForObject("SELECT version FROM work_orders WHERE id = ?", Integer.class, id));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ?", Integer.class, id));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ?", Integer.class, id));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, id));
        });
    }
    @Test void executesGuardedLifecycleWithAtomicHistoryAuditEventsAndWarranty() throws Exception {
        UUID id = order(); evidence(id);
        String[] targets = {"EM_DIAGNOSTICO", "AGUARDANDO_ORCAMENTO", "AGUARDANDO_APROVACAO", "APROVADA", "EM_MANUTENCAO", "AGUARDANDO_PECA", "EM_MANUTENCAO", "PRONTA", "ENTREGUE"};
        for (int i = 0; i < targets.length; i++) {
            transition(id, targets[i], i, "Autorizado pelo proprietário").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(targets[i])).andExpect(jsonPath("$.version").value(i + 1));
            if (targets[i].equals("AGUARDANDO_APROVACAO")) inTenant(fixture.tenantA, () -> {
                String hash = admin.queryForObject("SELECT quote_token_hash FROM work_order_transition_facts WHERE work_order_id = ?", String.class, id);
                assertNotNull(hash); assertEquals(64, hash.length());
                assertFalse(admin.queryForObject("SELECT details::text FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_STATUS_CHANGED' ORDER BY occurred_at DESC LIMIT 1", String.class, id).contains("publicToken"));
            });
        }
        inTenant(fixture.tenantA, () -> {
            assertEquals(10, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ?", Integer.class, id));
            assertEquals(9, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_STATUS_CHANGED'", Integer.class, id));
            assertEquals(10, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, id));
            assertTrue(admin.queryForObject("SELECT ready_at IS NOT NULL AND delivered_at IS NOT NULL AND warranty_days = 90 AND warranty_until IS NOT NULL FROM work_orders WHERE id = ?", Boolean.class, id));
            assertNull(admin.queryForObject("SELECT quote_token_hash FROM work_order_transition_facts WHERE work_order_id = ?", String.class, id));
        });
        transition(id, "RECEBIDA", 9, "Reabrir").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }
    @Test void guardsCannotBeBypassedWithRequestFlagsAndCancellationRequiresReason() throws Exception {
        UUID id = order();
        mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("toStatus", "EM_DIAGNOSTICO", "version", 0, "technicianAssigned", true, "diagnosisCompleted", true))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        transition(id, "CANCELADA", 0, " ").andExpect(status().isConflict());
        transition(id, "CANCELADA", 0, "Cliente desistiu").andExpect(status().isOk());
        transition(id, "RECEBIDA", 1, "Reabrir").andExpect(status().isConflict());
    }
    @Test void revisionInvalidatesTokenAndRequiresNewQuoteEvidence() throws Exception {
        UUID id = order(); evidence(id);
        transition(id, "EM_DIAGNOSTICO", 0, null).andExpect(status().isOk());
        transition(id, "AGUARDANDO_ORCAMENTO", 1, null).andExpect(status().isOk());
        transition(id, "AGUARDANDO_APROVACAO", 2, null).andExpect(status().isOk());
        transition(id, "AGUARDANDO_ORCAMENTO", 3, "Revisar").andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> {
            assertNull(admin.queryForObject("SELECT quote_token_hash FROM work_order_transition_facts WHERE work_order_id = ?", String.class, id));
            assertNull(admin.queryForObject("SELECT quote_id FROM work_order_transition_facts WHERE work_order_id = ?", UUID.class, id));
            assertEquals(2, admin.queryForObject("SELECT quote_version FROM work_order_transition_facts WHERE work_order_id = ?", Integer.class, id));
        });
        transition(id, "AGUARDANDO_APROVACAO", 4, null).andExpect(status().isConflict());
    }
    @Test void protectsTenantIsolationRoleAndExpectedVersion() throws Exception {
        UUID id = order(); evidence(id);
        transition(UUID.randomUUID(), "CANCELADA", 0, "Motivo").andExpect(status().isNotFound());
        UUID foreignCustomer = customer(fixture.tenantB, "Outro tenant");
        UUID foreignId = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> admin.update("INSERT INTO work_orders(id, tenant_id, customer_id) VALUES (?, ?, ?)", foreignId, fixture.tenantB, foreignCustomer));
        transition(foreignId, "CANCELADA", 0, "Motivo").andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId));
        token = login();
        transition(id, "EM_DIAGNOSTICO", 0, null).andExpect(status().isForbidden());
        transition(id, "CANCELADA", 0, "Desistência").andExpect(status().isOk());
        transition(id, "CANCELADA", 0, "Desistência").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_VERSION_CONFLICT"));
    }
    @Test void concurrentUpdatesWithTheSameVersionOnlyCommitOnce() throws Exception {
        UUID id = order();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> transition(id, "CANCELADA", 0, "Desistência").andReturn().getResponse().getStatus()));
            var statuses = new ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            Collections.sort(statuses); assertEquals(List.of(200, 409), statuses);
        }
        inTenant(fixture.tenantA, () -> assertEquals(2, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ?", Integer.class, id)));
    }
    @Test void outboxFailureRollsBackStatusVersionHistoryAndAudit() throws Exception {
        UUID id = order();
        String name = "fail_transition_" + id.toString().replace("-", "");
        admin.execute("CREATE FUNCTION public." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Forced test failure'; END; $$");
        try {
            admin.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON public.outbox_event FOR EACH ROW WHEN (NEW.aggregate_id = '" + id + "'::uuid) EXECUTE FUNCTION public." + name + "()");
            try {
                transition(id, "CANCELADA", 0, "Desistência").andExpect(status().isInternalServerError());
                inTenant(fixture.tenantA, () -> {
                    assertEquals("RECEBIDA", admin.queryForObject("SELECT status FROM work_orders WHERE id = ?", String.class, id));
                    assertEquals(0, admin.queryForObject("SELECT version FROM work_orders WHERE id = ?", Integer.class, id));
                    assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ?", Integer.class, id));
                    assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ?", Integer.class, id));
                    assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, id));
                });
            } finally { admin.execute("DROP TRIGGER " + name + " ON public.outbox_event"); }
        } finally { admin.execute("DROP FUNCTION public." + name + "()"); }
    }

    @Test void technicianCanOnlyReadAndAdvanceTheirAssignedOrder() throws Exception {
        UUID id = order(); evidence(id);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId));
        token = login();
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        transition(id, "EM_DIAGNOSTICO", 0, null).andExpect(status().isOk());
        transition(id, "CANCELADA", 1, "Desistência").andExpect(status().isForbidden());
        transition(id, "AGUARDANDO_ORCAMENTO", 1, null).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_transition_facts SET technician_id = NULL WHERE work_order_id = ?", id));
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        transition(id, "AGUARDANDO_APROVACAO", 2, null).andExpect(status().isForbidden());
    }
    @Test void validatesVersionAndStatusInputAndRejectsUnknownStates() throws Exception {
        UUID id = order();
        for (var body : List.of(Map.of("toStatus", "CANCELADA"), Map.of("toStatus", "CANCELADA", "version", -1), Map.of("toStatus", " ", "version", 0))) {
            mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json")
                    .content(mapper.writeValueAsString(body))).andExpect(status().isBadRequest());
        }
        transition(id, "UNKNOWN", 0, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }
    @Test void onlyOwnerCanAuthorizeAndAuditAnUnpaidDelivery() throws Exception {
        UUID id = order(); evidence(id);
        inTenant(fixture.tenantA, () -> {
            admin.update("UPDATE work_orders SET status = 'PRONTA' WHERE id = ?", id);
            admin.update("UPDATE work_order_transition_facts SET payment_settled = false WHERE work_order_id = ?", id);
            admin.update("UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId);
        });
        token = login();
        String content = mapper.writeValueAsString(Map.of("toStatus", "ENTREGUE", "version", 0, "reason", "Cobrança posterior autorizada", "paymentOverride", true));
        mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json").content(content)).andExpect(status().isForbidden());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'OWNER' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId));
        token = login();
        transition(id, "ENTREGUE", 0, null).andExpect(status().isConflict());
        mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json").content(content)).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> assertEquals("true", admin.queryForObject("SELECT details->>'paymentOverride' FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_STATUS_CHANGED'", String.class, id)));
    }

}
