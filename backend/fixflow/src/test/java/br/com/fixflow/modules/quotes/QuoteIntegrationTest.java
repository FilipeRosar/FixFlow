package br.com.fixflow.modules.quotes;

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
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "fixflow.quotes.link-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class QuoteIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate runtime;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    br.com.fixflow.shared.application.audit.AuditService audit;
    MembershipFixtures fixture;
    JdbcTemplate admin;
    TransactionTemplate transaction;
    String token;
    final String orders = "/api/v1/work-orders/";
    @BeforeEach void setup() throws Exception {
        fixture = new MembershipFixtures(environment, encoder);
        var ds = new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"), environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
        admin = new JdbcTemplate(ds); transaction = new TransactionTemplate(new DataSourceTransactionManager(ds)); token = login();
    }
    String login() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request", "true").contentType("application/json").content(mapper.writeValueAsString(Map.of("email", fixture.email, "password", fixture.password))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
    void inTenant(UUID tenant, Runnable action) {
        transaction.executeWithoutResult(tx -> { admin.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString()); action.run(); });
    }
    @AfterEach void cleanup() {
        for (var tenant : List.of(fixture.tenantA, fixture.tenantB)) inTenant(tenant, () -> {
            admin.update("DELETE FROM outbox_event WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM work_orders WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM customers WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM parts WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        });
        fixture.close();
    }
    UUID order(boolean diagnosed) throws Exception {
        UUID customer = UUID.randomUUID(), equipment = UUID.randomUUID();
        inTenant(fixture.tenantA, () -> {
            admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, 'Ana')", customer, fixture.tenantA);
            admin.update("INSERT INTO customer_contacts(tenant_id, customer_id, type, value, position) VALUES (?, ?, 'EMAIL', 'ana@example.test', 0)", fixture.tenantA, customer);
            admin.update("INSERT INTO equipment(id, tenant_id, customer_id, category, model) VALUES (?, ?, ?, 'NOTEBOOK', 'Dell')", equipment, fixture.tenantA, customer);
        });
        var result = mvc.perform(post("/api/v1/work-orders").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(Map.of("customerId", customer, "equipmentId", equipment, "reportedIssue", "Não liga", "receivedAccessories", List.of(), "physicalCondition", "Usado"))))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        if (diagnosed) {
            mvc.perform(patch(orders + id + "/technician").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(Map.of("version", 0, "technicianId", fixture.userId)))).andExpect(status().isOk());
            transition(id, "EM_DIAGNOSTICO", 1).andExpect(status().isOk());
            mvc.perform(post(orders + id + "/diagnosis").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(Map.of("version", 2, "findings", "Fonte queimada", "probableCause", "Curto", "proposedSolution", "Trocar fonte")))).andExpect(status().isCreated());
            transition(id, "AGUARDANDO_ORCAMENTO", 3).andExpect(status().isOk());
        }
        return id;
    }
    UUID part(UUID tenant) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> admin.update("INSERT INTO parts(id, tenant_id, sku, name, cost, sale_price, quantity_on_hand) VALUES (?, ?, ?, 'Fonte', 40, 70, 5)", id, tenant, id.toString()));
        return id;
    }
    Map<String, Object> item(String type, UUID part, String price) {
        var item = new HashMap<String, Object>();
        item.put("type", type); item.put("partId", part); item.put("description", type.equals("PART") ? "Fonte original" : "Mão de obra"); item.put("quantity", new BigDecimal("2")); item.put("unitPrice", new BigDecimal(price)); return item;
    }
    Map<String, Object> body(int version, List<Map<String, Object>> items, String type, String discount) {
        return Map.of("workOrderVersion", version, "items", items, "discountType", type, "discountValue", new BigDecimal(discount));
    }
    Map<String, Object> body(int version) { return body(version, List.of(item("SERVICE", null, "100")), "FIXED", "0"); }
    org.springframework.test.web.servlet.ResultActions save(UUID order, UUID quote, Map<String, Object> body) throws Exception {
        var request = quote == null ? post(orders + order + "/quotes") : put(orders + order + "/quotes/" + quote);
        return mvc.perform(request.header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body)));
    }
    org.springframework.test.web.servlet.ResultActions list(UUID order) throws Exception { return mvc.perform(get(orders + order + "/quotes").header("Authorization", "Bearer " + token)); }
    org.springframework.test.web.servlet.ResultActions transition(UUID id, String target, int version) throws Exception {
        if (target.equals("AGUARDANDO_APROVACAO")) {
            var quote = new java.util.concurrent.atomic.AtomicReference<UUID>();
            inTenant(fixture.tenantA, () -> quote.set(admin.queryForObject("SELECT id FROM quotes WHERE tenant_id = ? AND work_order_id = ? ORDER BY version DESC LIMIT 1", UUID.class, fixture.tenantA, id)));
            return mvc.perform(post(orders + id + "/quotes/" + quote.get() + "/send").header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("workOrderVersion", version))));
        }
        return mvc.perform(patch(orders + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(Map.of("toStatus", target, "version", version, "reason", "Decisão registrada pelo cliente"))));
    }
    UUID quote(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return UUID.fromString(mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("quotes").get(0).get("id").asText());
    }
    void role(String role) throws Exception {
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = ? WHERE tenant_id = ? AND user_id = ?", role, fixture.tenantA, fixture.userId)); token = login();
    }
    @Test void calculatesUpdatesDraftAndPreservesPricesWithoutConsumingStock() throws Exception {
        UUID order = order(true), part = part(fixture.tenantA);
        UUID quote = quote(save(order, null, body(4, List.of(item("SERVICE", null, "100"), item("PART", part, "70")), "PERCENT", "10"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.workOrderVersion").value(5))
                .andExpect(jsonPath("$.quotes[0].laborSubtotal").value(200)).andExpect(jsonPath("$.quotes[0].partsSubtotal").value(140))
                .andExpect(jsonPath("$.quotes[0].discountAmount").value(34)).andExpect(jsonPath("$.quotes[0].total").value(306)));
        inTenant(fixture.tenantA, () -> {
            admin.update("UPDATE parts SET name = 'Nome alterado', sale_price = 999 WHERE id = ?", part);
            assertEquals(new BigDecimal("5.000"), admin.queryForObject("SELECT quantity_on_hand FROM parts WHERE id = ?", BigDecimal.class, part));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM work_order_items WHERE work_order_id = ?", Integer.class, order));
        });
        list(order).andExpect(jsonPath("$.quotes[0].items[1].unitPrice").value(70)).andExpect(jsonPath("$.quotes[0].items[1].description").value("Fonte original"));
        save(order, quote, body(5)).andExpect(status().isOk()).andExpect(jsonPath("$.quotes[0].version").value(1)).andExpect(jsonPath("$.quotes[0].items.length()").value(1)).andExpect(jsonPath("$.quotes[0].total").value(200));
        save(order, quote, body(5)).andExpect(status().isConflict());
        save(order, null, body(6)).andExpect(status().isConflict());
    }
    @Test void sendingFreezesTermsTotalsItemsAndRevisionCreatesANewVersion() throws Exception {
        UUID order = order(true);
        inTenant(fixture.tenantA, () -> admin.update("INSERT INTO tenant_settings(tenant_id, warranty_terms, quote_validity_days) VALUES (?, 'Termos no envio', 10)", fixture.tenantA));
        UUID first = quote(save(order, null, body(4)).andExpect(status().isCreated()));
        transition(order, "AGUARDANDO_APROVACAO", 5).andExpect(status().isOk());
        list(order).andExpect(jsonPath("$.quotes[0].status").value("SENT")).andExpect(jsonPath("$.quotes[0].sentAt").isNotEmpty()).andExpect(jsonPath("$.quotes[0].warrantyTerms").value("Termos no envio"));
        inTenant(fixture.tenantA, () -> {
            admin.update("UPDATE tenant_settings SET warranty_terms = 'Termos novos', quote_validity_days = 1 WHERE tenant_id = ?", fixture.tenantA);
            assertTrue(admin.queryForObject("SELECT valid_until = sent_at + interval '10 days' FROM quotes WHERE id = ?", Boolean.class, first));
        });
        transition(order, "AGUARDANDO_ORCAMENTO", 6).andExpect(status().isOk());
        save(order, first, body(7)).andExpect(status().isConflict());
        UUID second = quote(save(order, null, body(7, List.of(item("SERVICE", null, "300")), "FIXED", "50")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.quotes[0].version").value(2)).andExpect(jsonPath("$.quotes[0].total").value(550))
                .andExpect(jsonPath("$.quotes[1].total").value(200)).andExpect(jsonPath("$.quotes[1].warrantyTerms").value("Termos no envio")));
        assertNotEquals(first, second);
        for (String sql : List.of("UPDATE quotes SET total = 1, discount_amount = labor_subtotal + parts_subtotal - 1 WHERE id = ?", "UPDATE quotes SET status = 'DRAFT', sent_at = NULL WHERE id = ?", "UPDATE quote_items SET unit_price = 1 WHERE quote_id = ?", "DELETE FROM quote_items WHERE quote_id = ?"))
            assertThrows(org.springframework.dao.DataAccessException.class, () -> inTenant(fixture.tenantA, () -> admin.update(sql, first)));
        transition(order, "AGUARDANDO_APROVACAO", 8).andExpect(status().isOk());
        transition(order, "APROVADA", 9).andExpect(status().isOk());
        list(order).andExpect(jsonPath("$.quotes[0].status").value("APPROVED")).andExpect(jsonPath("$.quotes[1].status").value("SENT"));
        save(order, second, body(10)).andExpect(status().isConflict());
    }
    @Test void validatesPayloadsDiagnosisStateAndTenantReferences() throws Exception {
        UUID received = order(false);
        save(received, null, body(0)).andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_orders SET status = 'AGUARDANDO_ORCAMENTO' WHERE id = ?", received));
        save(received, null, body(0)).andExpect(status().isConflict());
        UUID order = order(true), otherPart = part(fixture.tenantB);
        save(order, null, body(4, List.of(item("PART", otherPart, "1")), "FIXED", "0")).andExpect(status().isNotFound());
        for (var body : List.of(body(4, List.of(), "FIXED", "0"), body(4, List.of(item("SERVICE", null, "100")), "PERCENT", "101"), body(4, List.of(item("SERVICE", null, "100")), "FIXED", "201"), body(4, List.of(item("SERVICE", null, "-1")), "FIXED", "0"), body(4, List.of(item("PART", null, "1")), "FIXED", "0")))
            save(order, null, body).andExpect(status().isBadRequest());
        list(order).andExpect(jsonPath("$.quotes.length()").value(0)).andExpect(jsonPath("$.workOrderVersion").value(4));
    }
    @Test void permissionsAndRlsRestrictOtherCompaniesAndUnassignedTechnicians() throws Exception {
        UUID order = order(true);
        mvc.perform(get(orders + order + "/quotes")).andExpect(status().isUnauthorized());
        role("TECHNICIAN");
        UUID quote = quote(save(order, null, body(4)).andExpect(status().isCreated()));
        transition(order, "AGUARDANDO_APROVACAO", 5).andExpect(status().isForbidden());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_transition_facts SET technician_id = NULL WHERE work_order_id = ?", order));
        list(order).andExpect(status().isForbidden()); save(order, quote, body(5)).andExpect(status().isForbidden());
        role("ATTENDANT"); save(order, quote, body(5)).andExpect(status().isOk());
        UUID customer = UUID.randomUUID(), otherOrder = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> {
            admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, 'Outro')", customer, fixture.tenantB);
            admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, status) VALUES (?, ?, ?, 'RECEBIDA')", otherOrder, fixture.tenantB, customer);
        });
        list(otherOrder).andExpect(status().isNotFound());
        save(otherOrder, null, body(0)).andExpect(status().isNotFound());
        assertEquals(0, runtime.queryForObject("SELECT count(*) FROM quotes WHERE id = ?", Integer.class, quote));
    }
    @Test void concurrentDraftSavesHaveExactlyOneWinner() throws Exception {
        UUID order = order(true);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var calls = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) calls.add(pool.submit(() -> { start.await(); return save(order, null, body(4)).andReturn().getResponse().getStatus(); }));
            start.countDown();
            var statuses = new ArrayList<Integer>(); for (var call : calls) statuses.add(call.get());
            Collections.sort(statuses); assertEquals(List.of(201, 409), statuses);
        }
        list(order).andExpect(jsonPath("$.quotes.length()").value(1)).andExpect(jsonPath("$.workOrderVersion").value(5));
    }
    @Test void concurrentEditAndSendCannotFreezeStalePrices() throws Exception {
        UUID order = order(true);
        UUID quote = quote(save(order, null, body(4)).andExpect(status().isCreated()));
        var start = new java.util.concurrent.CountDownLatch(1);
        int editStatus, sendStatus;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var edit = pool.submit(() -> { start.await(); return save(order, quote, body(5, List.of(item("SERVICE", null, "300")), "FIXED", "0")).andReturn().getResponse().getStatus(); });
            var send = pool.submit(() -> { start.await(); return transition(order, "AGUARDANDO_APROVACAO", 5).andReturn().getResponse().getStatus(); });
            start.countDown(); editStatus = edit.get(); sendStatus = send.get();
        }
        assertTrue(editStatus == 200 && sendStatus == 409 || editStatus == 409 && sendStatus == 200);
        if (editStatus == 200) transition(order, "AGUARDANDO_APROVACAO", 6).andExpect(status().isOk());
        list(order).andExpect(jsonPath("$.quotes[0].status").value("SENT"))
                .andExpect(jsonPath("$.quotes[0].total").value(editStatus == 200 ? 600 : 200));
        inTenant(fixture.tenantA, () -> assertTrue(admin.queryForObject("SELECT q.total = f.quote_total FROM quotes q JOIN work_order_transition_facts f ON f.tenant_id = q.tenant_id AND f.quote_id = q.id WHERE q.id = ?", Boolean.class, quote)));
    }
    @Test void auditFailureRollsBackDraftItemsAndProjection() throws Exception {
        UUID order = order(true);
        br.com.fixflow.shared.application.audit.AuditService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(target).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("QUOTE_DRAFT_SAVED"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try { save(order, null, body(4)).andExpect(status().isInternalServerError()); }
        finally { org.mockito.Mockito.reset(target); }
        list(order).andExpect(jsonPath("$.quotes.length()").value(0)).andExpect(jsonPath("$.workOrderVersion").value(4));
        inTenant(fixture.tenantA, () -> assertNull(admin.queryForObject("SELECT quote_id FROM work_order_transition_facts WHERE work_order_id = ?", UUID.class, order)));
    }
}
