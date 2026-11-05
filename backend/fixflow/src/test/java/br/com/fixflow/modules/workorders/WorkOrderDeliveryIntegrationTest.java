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
class WorkOrderDeliveryIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    br.com.fixflow.shared.application.audit.AuditService audit;
    @Autowired JdbcTemplate runtimeJdbc;
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
        inTenant(fixture.tenantA, () -> admin.update("INSERT INTO work_order_diagnoses(id, tenant_id, work_order_id, revision, findings, probable_cause, proposed_solution, recorded_by) VALUES (?, ?, ?, 1, 'Achados', 'Causa', 'Solução', ?)", UUID.randomUUID(), fixture.tenantA, id, fixture.userId));
    }
    org.springframework.test.web.servlet.ResultActions transition(UUID id, String target, int version, String reason) throws Exception {
        var body = new HashMap<String, Object>(); body.put("toStatus", target); body.put("version", version); body.put("reason", reason);
        return mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(mapper.writeValueAsString(body)));
    }

    UUID ready(boolean paid) throws Exception {
        UUID id = order(); evidence(id);
        inTenant(fixture.tenantA, () -> {
            admin.update("UPDATE work_orders SET status = 'PRONTA', ready_at = clock_timestamp() WHERE id = ?", id);
            admin.update("UPDATE work_order_transition_facts SET payment_settled = ? WHERE work_order_id = ?", paid, id);
            admin.update("INSERT INTO tenant_settings(tenant_id, warranty_terms) VALUES (?, 'Termos registrados na entrega') ON CONFLICT (tenant_id) DO UPDATE SET warranty_terms = EXCLUDED.warranty_terms", fixture.tenantA);
        });
        return id;
    }
    void role(String role) throws Exception {
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = ? WHERE tenant_id = ? AND user_id = ?", role, fixture.tenantA, fixture.userId)); token = login();
    }
    org.springframework.test.web.servlet.ResultActions deliver(UUID id, int version, int days, boolean override, String reason) throws Exception {
        var body = new HashMap<String, Object>(); body.put("version", version); body.put("warrantyDays", days); body.put("paymentOverride", override); body.put("reason", reason);
        return mvc.perform(post(url + "/" + id + "/deliver").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body)));
    }
    @Test void paidDeliveryCalculatesAndReturnsWarrantyWithImmutableTermsAndHistory() throws Exception {
        UUID id = ready(true); role("ATTENDANT");
        deliver(id, 0, 90, false, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENTREGUE"))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.deliveredAt").exists())
                .andExpect(jsonPath("$.warrantyDays").value(90)).andExpect(jsonPath("$.warrantyUntil").exists())
                .andExpect(jsonPath("$.paymentSettled").value(true)).andExpect(jsonPath("$.deliveryPaymentOverride").value(false))
                .andExpect(jsonPath("$.deliveredByName").value("Integration identity"))
                .andExpect(jsonPath("$.warrantyTerms").value("Termos registrados na entrega"));
        inTenant(fixture.tenantA, () -> {
            assertTrue(admin.queryForObject("SELECT warranty_until = (delivered_at AT TIME ZONE 'America/Sao_Paulo')::date + 90 FROM work_orders WHERE id = ?", Boolean.class, id));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'WorkOrderDelivered'", Integer.class, id));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_UNPAID_DELIVERY_AUTHORIZED'", Integer.class, id));
        });
        mvc.perform(get(url + "/" + id + "/timeline").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].toStatus").value("ENTREGUE"))
                .andExpect(jsonPath("$.content[0].details.warrantyDays").value(90))
                .andExpect(jsonPath("$.content[0].details.paymentOverride").value(false));
    }
    @Test void unpaidDeliveryCannotBeFakedAndOnlyOwnerMayAuthorizeWithReason() throws Exception {
        UUID id = ready(false);
        deliver(id, 0, 30, false, null).andExpect(status().isConflict());
        mvc.perform(post(url + "/" + id + "/deliver").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"version\":0,\"warrantyDays\":30,\"paymentSettled\":true,\"financialStatus\":\"PAID\"}")).andExpect(status().isConflict());
        role("TECHNICIAN"); deliver(id, 0, 30, true, "Autorizar").andExpect(status().isForbidden());
        role("ATTENDANT"); deliver(id, 0, 30, true, "Autorizar").andExpect(status().isForbidden());
        role("OWNER"); deliver(id, 0, 30, true, " ").andExpect(status().isConflict());
        deliver(id, 0, 30, true, "  Cobrança posterior autorizada  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentSettled").value(false)).andExpect(jsonPath("$.deliveryPaymentOverride").value(true))
                .andExpect(jsonPath("$.deliveryReason").value("Cobrança posterior autorizada"));
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'WORK_ORDER_UNPAID_DELIVERY_AUTHORIZED' AND actor_id = ? AND details->>'reason' = 'Cobrança posterior autorizada'", Integer.class, id, fixture.userId));
            assertFalse(admin.queryForObject("SELECT payment_settled FROM work_order_transition_facts WHERE work_order_id = ?", Boolean.class, id));
        });
    }
    @Test void deliveredIsTerminalAndRetriesNeverExtendWarrantyOrDuplicateEvents() throws Exception {
        UUID id = ready(true);
        String delivered = deliver(id, 0, 90, false, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var target : br.com.fixflow.modules.workorders.domain.WorkOrderStatus.values())
            transition(id, target.name(), 1, "Reabrir").andExpect(status().isConflict());
        deliver(id, 1, 3650, false, null).andExpect(status().isConflict());
        deliver(id, 0, 90, false, null).andExpect(status().isConflict());
        String after = mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(delivered, after);
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'WorkOrderDelivered'", Integer.class, id)));
    }
    @Test void databaseAlsoPreventsReopeningOrChangingTheDeliverySnapshot() throws Exception {
        UUID id = ready(true); deliver(id, 0, 90, false, null).andExpect(status().isOk());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(runtimeJdbc.getDataSource()));
        for (String sql : List.of("UPDATE work_orders SET status = 'RECEBIDA' WHERE id = ?", "UPDATE work_orders SET warranty_days = 1 WHERE id = ?", "UPDATE work_orders SET delivery_payment_override = true WHERE id = ?")) {
            tx.executeWithoutResult(status -> {
                runtimeJdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, fixture.tenantA.toString());
                assertThrows(org.springframework.dao.DataAccessException.class, () -> runtimeJdbc.update(sql, id));
                status.setRollbackOnly();
            });
        }
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENTREGUE")).andExpect(jsonPath("$.warrantyDays").value(90));
    }
    @Test void rejectsMissingOrOutOfRangeWarrantyAndUsesNullForZeroAdditionalDays() throws Exception {
        UUID id = ready(true);
        for (int days : List.of(-1, 3651)) deliver(id, 0, days, false, null).andExpect(status().isBadRequest());
        mvc.perform(post(url + "/" + id + "/deliver").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"version\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post(url + "/" + id + "/deliver").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"version\":0,\"warrantyDays\":1.5}")).andExpect(status().isBadRequest());
        deliver(id, 0, 0, false, null).andExpect(status().isOk()).andExpect(jsonPath("$.warrantyDays").value(0)).andExpect(jsonPath("$.warrantyUntil").doesNotExist());
    }
    @Test void unrepairedReturnStillRequiresPaymentAndDiagnosticFeeButHasNoAdditionalWarranty() throws Exception {
        UUID id = ready(false);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_orders SET status = 'SEM_REPARO' WHERE id = ?", id));
        deliver(id, 0, 0, false, null).andExpect(status().isConflict());
        deliver(id, 0, 90, true, "Autorizar").andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_transition_facts SET diagnostic_fee_registered = false WHERE work_order_id = ?", id));
        deliver(id, 0, 0, true, "Autorizar").andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_transition_facts SET diagnostic_fee_registered = true WHERE work_order_id = ?", id));
        deliver(id, 0, 0, true, "Autorizar").andExpect(status().isOk()).andExpect(jsonPath("$.warrantyUntil").doesNotExist()).andExpect(jsonPath("$.warrantyDays").value(0));
    }
    @Test void failureInOwnerExceptionAuditRollsBackDeliveryWarrantyHistoryAndOutbox() throws Exception {
        UUID id = ready(false);
        br.com.fixflow.shared.application.audit.AuditService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("Audit unavailable")).when(target).record(org.mockito.ArgumentMatchers.eq(fixture.userId),
                org.mockito.ArgumentMatchers.eq("WORK_ORDER_UNPAID_DELIVERY_AUTHORIZED"), org.mockito.ArgumentMatchers.eq("WORK_ORDER"), org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
        deliver(id, 0, 90, true, "Autorizado").andExpect(status().isInternalServerError());
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PRONTA"))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.deliveredAt").doesNotExist()).andExpect(jsonPath("$.warrantyUntil").doesNotExist());
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ?", Integer.class, id));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_timeline WHERE work_order_id = ?", Integer.class, id));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'WorkOrderDelivered'", Integer.class, id));
        });
    }
    @Test void concurrentDeliveriesProduceExactlyOneReceiptAndOneDeliveryEvent() throws Exception {
        UUID id = ready(true); var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int days : List.of(30, 90)) futures.add(pool.submit(() -> { start.await(); return deliver(id, 0, days, false, null).andReturn().getResponse().getStatus(); }));
            start.countDown(); var results = new ArrayList<Integer>();
            for (var future : futures) results.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
            Collections.sort(results); assertEquals(List.of(200, 409), results);
            inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'WorkOrderDelivered'", Integer.class, id)));
        } finally { pool.shutdownNow(); }
    }
    @Test void laterChangesToCompanyTermsOrUserNameDoNotRewriteTheReceipt() throws Exception {
        UUID id = ready(true); deliver(id, 0, 90, false, null).andExpect(status().isOk());
        admin.update("UPDATE users SET full_name = 'Outro nome' WHERE id = ?", fixture.userId);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_settings SET warranty_terms = 'Termos novos' WHERE tenant_id = ?", fixture.tenantA));
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveredByName").value("Integration identity")).andExpect(jsonPath("$.warrantyTerms").value("Termos registrados na entrega"));
    }
    @Test void legacyStatusEndpointKeepsTheSamePaymentGuardAndServerWarranty() throws Exception {
        UUID id = ready(false);
        transition(id, "ENTREGUE", 0, null).andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_transition_facts SET payment_settled = true WHERE work_order_id = ?", id));
        transition(id, "ENTREGUE", 0, null).andExpect(status().isOk()).andExpect(jsonPath("$.warrantyDays").value(90));
    }
}
