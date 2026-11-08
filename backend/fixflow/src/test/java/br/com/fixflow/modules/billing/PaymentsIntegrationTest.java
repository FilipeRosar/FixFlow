package br.com.fixflow.modules.billing;

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
class PaymentsIntegrationTest {
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
    void approve(UUID order) {
        inTenant(fixture.tenantA, () -> admin.update("""
                INSERT INTO quotes(id, tenant_id, work_order_id, version, status, discount_type, discount_value,
                    labor_subtotal, parts_subtotal, discount_amount, total, valid_until, sent_at)
                VALUES (?, ?, ?, 1, 'APPROVED', 'FIXED', 0, 100, 0, 0, 100, CURRENT_TIMESTAMP + interval '1 day', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.tenantA, order));
    }
    org.springframework.test.web.servlet.ResultActions pay(UUID order, String key, String amount) throws Exception {
        return mvc.perform(post(url + "/" + order + "/payments").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType("application/json")
                .content("{\"amount\":" + amount + ",\"method\":\"PIX\",\"paidAt\":\"2026-11-08T12:00:00Z\"}"));
    }
    void financial(UUID order, String status, boolean settled) throws Exception {
        mvc.perform(get(url + "/" + order).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.financialStatus").value(status))
                .andExpect(jsonPath("$.paymentSettled").value(settled));
    }
    @Test void sumsConfirmedPaymentsAndRetriesWithoutDuplicating() throws Exception {
        UUID order = order(); approve(order);
        financial(order, "UNPAID", false);
        String first = pay(order, "first", "40.00").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        financial(order, "PARTIAL", false);
        pay(order, "first", "40.0").andExpect(status().isOk()).andExpect(content().json(first));
        pay(order, "first", "41").andExpect(status().isConflict());
        pay(order, "second", "60").andExpect(status().isCreated());
        financial(order, "PAID", true);
        mvc.perform(get(url + "/" + order + "/payments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        inTenant(fixture.tenantA, () -> assertEquals(2, admin.queryForObject(
                "SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'PAYMENT_REGISTERED'", Integer.class, order)));
    }
    @Test void acceptsFullAndExcessPaymentsAndRejectsMissingApprovalOrInvalidAmounts() throws Exception {
        UUID order = order();
        pay(order, "missing", "100").andExpect(status().isConflict());
        approve(order);
        for (String amount : List.of("0", "-1", "1.001", "1000000000000"))
            pay(order, "invalid", amount).andExpect(status().isBadRequest());
        pay(order, "full", "100").andExpect(status().isCreated());
        financial(order, "PAID", true);
        pay(order, "extra", "1").andExpect(status().isCreated());
        financial(order, "PAID", true);
    }
    @Test void keyCannotBeReusedAcrossOrdersAndOtherTenantsAreHidden() throws Exception {
        UUID order = order(); approve(order);
        pay(order, "same", "10").andExpect(status().isCreated());
        UUID customer = customer(fixture.tenantA, "Bia");
        UUID other = open(customer, equipment(fixture.tenantA, customer), 2); approve(other);
        pay(other, "same", "10").andExpect(status().isConflict());
        UUID foreignOrder = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, status) VALUES (?, ?, ?, 'RECEBIDA')",
                foreignOrder, fixture.tenantB, customer(fixture.tenantB, "Outro")));
        pay(foreignOrder, "hidden", "10").andExpect(status().isNotFound());
    }
    @Test void onlyOwnerAndAttendantMayRegisterOrList() throws Exception {
        UUID order = order(); approve(order);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId));
        token = login();
        pay(order, "attendant", "10").andExpect(status().isCreated());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId));
        token = login();
        pay(order, "technician", "10").andExpect(status().isForbidden());
        mvc.perform(get(url + "/" + order + "/payments").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void concurrentRetriesCreateOnePaymentAndAuditFailureRollsBack() throws Exception {
        UUID order = order(); approve(order);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> request = () -> {
                start.await();
                return pay(order, "concurrent", "40").andReturn().getResponse().getStatus();
            };
            var first = pool.submit(request); var second = pool.submit(request); start.countDown();
            assertEquals(Set.of(200, 201), Set.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)));
        }
        financial(order, "PARTIAL", false);
        var auditTarget = org.springframework.test.util.AopTestUtils.<br.com.fixflow.shared.application.audit.AuditService>getUltimateTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("Audit unavailable")).when(auditTarget)
                .record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("PAYMENT_REGISTERED"),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        try { pay(order, "rollback", "60"); } catch (Exception expected) { /* MVC may propagate the injected failure. */ }
        finally { org.mockito.Mockito.reset(auditTarget); }
        financial(order, "PARTIAL", false);
        pay(order, "rollback", "60").andExpect(status().isCreated());
        financial(order, "PAID", true);
    }

    @Test void actualSettlementUnlocksDelivery() throws Exception {
        UUID order = order(); approve(order);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_orders SET status = 'PRONTA', ready_at = CURRENT_TIMESTAMP WHERE id = ?", order));
        String delivery = "{\"version\":0,\"warrantyDays\":90}";
        mvc.perform(post(url + "/" + order + "/deliver").header("Authorization", "Bearer " + token).contentType("application/json").content(delivery))
                .andExpect(status().isConflict());
        UUID settlement = payment(order, "settlement", "100");
        mvc.perform(post(url + "/" + order + "/deliver").header("Authorization", "Bearer " + token).contentType("application/json").content(delivery))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENTREGUE"))
                .andExpect(jsonPath("$.paymentSettled").value(true)).andExpect(jsonPath("$.financialStatus").value("PAID"));
        voidPayment(settlement, "Correção após entrega").andExpect(status().isOk());
        financial(order, "UNPAID", false);
        mvc.perform(get(url + "/" + order).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENTREGUE"));
    }

    UUID payment(UUID order, String key, String amount) throws Exception {
        return UUID.fromString(mapper.readTree(pay(order, key, amount).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText());
    }
    org.springframework.test.web.servlet.ResultActions voidPayment(UUID payment, String reason) throws Exception {
        return mvc.perform(post("/api/v1/payments/" + payment + "/void").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("reason", reason))));
    }
    @Test void voidPreservesEvidenceRecalculatesAndAuditsExactlyOnce() throws Exception {
        UUID order = order(); approve(order);
        UUID first = payment(order, "first", "40"); UUID second = payment(order, "second", "60");
        financial(order, "PAID", true);
        String reason = "Lançamento duplicado: \"Pix\"";
        var result = voidPayment(second, reason).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED")).andExpect(jsonPath("$.amount").value(60))
                .andExpect(jsonPath("$.voidReason").value(reason)).andExpect(jsonPath("$.voidedBy").value(fixture.userId.toString()))
                .andExpect(jsonPath("$.voidedAt").isNotEmpty()).andReturn().getResponse().getContentAsString();
        financial(order, "PARTIAL", false);
        voidPayment(second, reason).andExpect(status().isOk()).andExpect(content().json(result));
        voidPayment(second, "Outro motivo").andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> {
            assertEquals(2, admin.queryForObject("SELECT count(*) FROM payments WHERE work_order_id = ?", Integer.class, order));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'PAYMENT_VOIDED'", Integer.class, order));
            assertEquals(reason, admin.queryForObject("SELECT details->>'reason' FROM audit_log WHERE resource_id = ? AND action = 'PAYMENT_VOIDED'", String.class, order));
        });
        voidPayment(first, "Recebimento cancelado").andExpect(status().isOk());
        financial(order, "UNPAID", false);
        mvc.perform(get(url + "/" + order + "/payments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        // Retrying the original registration must never revive the voided payment.
        pay(order, "first", "40").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
    }
    @Test void voidRequiresReasonOwnerAndTenantIsolation() throws Exception {
        UUID order = order(); approve(order); UUID payment = payment(order, "one", "100");
        for (String reason : List.of("", "   ", "x".repeat(2001))) voidPayment(payment, reason).andExpect(status().isBadRequest());
        for (String role : List.of("ATTENDANT", "TECHNICIAN")) {
            inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = ? WHERE tenant_id = ? AND user_id = ?", role, fixture.tenantA, fixture.userId));
            token = login(); voidPayment(payment, "Correção").andExpect(status().isForbidden());
        }
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'OWNER' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId)); token = login();
        UUID foreignCustomer = customer(fixture.tenantB, "Outro"); UUID foreignOrder = UUID.randomUUID(); UUID foreignPayment = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> {
            admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, status) VALUES (?, ?, ?, 'RECEBIDA')", foreignOrder, fixture.tenantB, foreignCustomer);
            admin.update("INSERT INTO payments(id, tenant_id, work_order_id, amount, method, paid_at, idempotency_key, created_by) VALUES (?, ?, ?, 10, 'PIX', CURRENT_TIMESTAMP, 'foreign', ?)", foreignPayment, fixture.tenantB, foreignOrder, fixture.userId);
        });
        voidPayment(foreignPayment, "Correção").andExpect(status().isNotFound());
        voidPayment(UUID.randomUUID(), "Correção").andExpect(status().isNotFound());
        financial(order, "PAID", true);
    }
    @Test void voidRollsBackOnAuditFailureAndConcurrentRetriesKeepOneAudit() throws Exception {
        UUID order = order(); approve(order); UUID payment = payment(order, "one", "100");
        var target = org.springframework.test.util.AopTestUtils.<br.com.fixflow.shared.application.audit.AuditService>getUltimateTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("Audit unavailable")).when(target)
                .record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("PAYMENT_VOIDED"),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        try { voidPayment(payment, "Correção"); } catch (Exception expected) { /* MVC may propagate the injected failure. */ }
        finally { org.mockito.Mockito.reset(target); }
        financial(order, "PAID", true);
        inTenant(fixture.tenantA, () -> assertEquals("CONFIRMED", admin.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment)));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> request = () -> { start.await(); return voidPayment(payment, "Correção").andReturn().getResponse().getStatus(); };
            var first = pool.submit(request); var second = pool.submit(request); start.countDown();
            assertEquals(200, first.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(200, second.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        financial(order, "UNPAID", false);
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'PAYMENT_VOIDED'", Integer.class, order)));
    }

    @Test void runtimeCannotDeleteOrRewritePaymentEvidence() throws Exception {
        UUID order = order(); approve(order); UUID payment = payment(order, "one", "100");
        voidPayment(payment, "Correção").andExpect(status().isOk());
        var runtimeTransaction = new TransactionTemplate(new DataSourceTransactionManager(runtimeJdbc.getDataSource()));
        for (String sql : List.of("DELETE FROM payments WHERE id = ?", "UPDATE payments SET amount = 1 WHERE id = ?",
                "UPDATE payments SET void_reason = 'Reescrito' WHERE id = ?", "UPDATE payments SET status = 'CONFIRMED' WHERE id = ?")) {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> runtimeTransaction.executeWithoutResult(tx -> {
                runtimeJdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, fixture.tenantA.toString());
                runtimeJdbc.update(sql, payment);
            }));
        }
        inTenant(fixture.tenantA, () -> {
            assertEquals("Correção", admin.queryForObject("SELECT void_reason FROM payments WHERE id = ?", String.class, payment));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM payments WHERE id = ?", Integer.class, payment));
        });
    }

}
