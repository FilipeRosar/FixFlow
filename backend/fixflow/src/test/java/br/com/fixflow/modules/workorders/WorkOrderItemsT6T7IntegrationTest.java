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
class WorkOrderItemsT6T7IntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    br.com.fixflow.shared.application.audit.AuditService audit;
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
        if (fixture == null) return;
        for (var tenant : List.of(fixture.tenantA, fixture.tenantB)) inTenant(tenant, () -> {
            admin.update("DELETE FROM outbox_event WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM inventory_movements WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM work_orders WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM parts WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM customers WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        });
        fixture.close();
    }
    @Test void receivesAtWeightedAverageAndRequiresOwnerAdjustmentReason() throws Exception {
        var created = mvc.perform(post("/api/v1/parts").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"sku\":\"ENTRY-TEST\",\"name\":\"Peça\",\"unit\":\"UN\",\"cost\":10,\"salePrice\":30,\"minQuantity\":0}"))
                .andExpect(status().isCreated()).andReturn();
        String partId = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String endpoint = "/api/v1/parts/" + partId;
        mvc.perform(post(endpoint + "/entries").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"quantity\":2,\"unitCost\":10,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cost").value(10));
        mvc.perform(post(endpoint + "/entries").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"quantity\":3,\"unitCost\":20,\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cost").value(16)).andExpect(jsonPath("$.quantityOnHand").value(5));
        mvc.perform(post(endpoint + "/adjustments").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"quantity\":-1,\"reason\":\" \",\"version\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(endpoint + "/adjustments").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"quantity\":-1,\"reason\":\"Contagem física\",\"version\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cost").value(16)).andExpect(jsonPath("$.quantityOnHand").value(4));
        mvc.perform(post(endpoint + "/entries").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"quantity\":1,\"unitCost\":10,\"version\":2}"))
                .andExpect(status().isConflict());
        role("ATTENDANT");
        mvc.perform(post(endpoint + "/adjustments").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"quantity\":1,\"reason\":\"Contagem\",\"version\":3}"))
                .andExpect(status().isForbidden());
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
    org.springframework.test.web.servlet.ResultActions transition(UUID id, String target, int version) throws Exception {
        return mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(Map.of("toStatus", target, "version", version))));
    }
    void role(String role) throws Exception {
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = ? WHERE tenant_id = ? AND user_id = ?", role, fixture.tenantA, fixture.userId)); token = login();
    }

    UUID maintenanceOrder() throws Exception {
        UUID customer = customer(fixture.tenantA, "Ana");
        UUID equipment = equipment(fixture.tenantA, customer);
        UUID id = UUID.randomUUID();
        inTenant(fixture.tenantA, () -> {
            admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, equipment_id, number, status, reported_issue) VALUES (?, ?, ?, ?, ?, 'EM_MANUTENCAO', 'Teste')", id, fixture.tenantA, customer, equipment, 1 + Math.abs(id.getMostSignificantBits() % 100000000));
            admin.update("INSERT INTO work_order_transition_facts(tenant_id, work_order_id, technician_id, maintenance_started) VALUES (?, ?, ?, true)", fixture.tenantA, id, fixture.userId);
        });
        return id;
    }
    UUID part(UUID tenant, String quantity) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> {
            admin.update("INSERT INTO parts(id, tenant_id, sku, name, cost, sale_price, quantity_on_hand) VALUES (?, ?, ?, 'Tela', 10, 20, ?)", id, tenant, id.toString(), new java.math.BigDecimal(quantity));
            if (new java.math.BigDecimal(quantity).signum() > 0)
                admin.update("INSERT INTO inventory_movements(id, tenant_id, part_id, type, quantity, unit_cost, created_by) VALUES (?, ?, ?, 'ENTRY', ?, 10, ?)", UUID.randomUUID(), tenant, id, new java.math.BigDecimal(quantity), fixture.userId);
        });
        return id;
    }
    Map<String, Object> item(UUID id, UUID part, int version, String quantity) {
        var body = new HashMap<String, Object>();
        body.put("id", id); body.put("version", version); body.put("type", part == null ? "SERVICE" : "PART");
        body.put("partId", part); body.put("description", part == null ? "Mão de obra" : "Tela");
        body.put("quantity", new java.math.BigDecimal(quantity)); body.put("unitPrice", 20);
        return body;
    }
    org.springframework.test.web.servlet.ResultActions add(UUID order, Map<String, Object> body) throws Exception {
        return mvc.perform(post(url + "/" + order + "/items").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body)));
    }
    org.springframework.test.web.servlet.ResultActions remove(UUID order, UUID item, int version) throws Exception {
        return mvc.perform(delete(url + "/" + order + "/items/" + item).param("version", "" + version).header("Authorization", "Bearer " + token));
    }
    void balance(UUID part, String expected) {
        inTenant(fixture.tenantA, () -> {
            assertEquals(0, new java.math.BigDecimal(expected).compareTo(admin.queryForObject("SELECT quantity_on_hand FROM parts WHERE id = ?", java.math.BigDecimal.class, part)));
            assertEquals(0, new java.math.BigDecimal(expected).compareTo(admin.queryForObject("SELECT coalesce(sum(quantity),0) FROM inventory_movements WHERE part_id = ?", java.math.BigDecimal.class, part)));
        });
    }
    @Test void t6ConsumesAndReturnsAtomicallyWithIdempotentRetriesAndSnapshotCost() throws Exception {
        UUID order = maintenanceOrder(), part = part(fixture.tenantA, "2"), item = UUID.randomUUID();
        var body = item(item, part, 0, "1.25");
        add(order, body).andExpect(status().isCreated()).andExpect(jsonPath("$.workOrderVersion").value(1)).andExpect(jsonPath("$.items[0].unitCost").value(10));
        add(order, body).andExpect(status().isCreated()).andExpect(jsonPath("$.workOrderVersion").value(1));
        balance(part, "0.75");
        add(order, item(item, part, 1, "1")).andExpect(status().isConflict());
        remove(order, item, 1).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        remove(order, item, 1).andExpect(status().isOk()).andExpect(jsonPath("$.workOrderVersion").value(2));
        balance(part, "2");
        add(order, body).andExpect(status().isCreated()).andExpect(jsonPath("$.items.length()").value(0));
        balance(part, "2");
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM inventory_movements WHERE item_id = ? AND type = 'EXIT'", Integer.class, item));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM inventory_movements WHERE item_id = ? AND type = 'RETURN'", Integer.class, item));
        });
    }
    @Test void t6InsufficientStockPersistsNothingAndDoesNotAdvanceVersion() throws Exception {
        UUID order = maintenanceOrder(), part = part(fixture.tenantA, "1"), item = UUID.randomUUID();
        add(order, item(item, part, 0, "2")).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        balance(part, "1");
        inTenant(fixture.tenantA, () -> {
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM work_order_items WHERE id = ?", Integer.class, item));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM inventory_movements WHERE item_id = ?", Integer.class, item));
            assertEquals(0, admin.queryForObject("SELECT version FROM work_orders WHERE id = ?", Integer.class, order));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ?", Integer.class, order));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, order));
        });
    }
    @Test void t7TwoOrdersCompetingForLastUnitHaveExactlyOneSuccess() throws Exception {
        UUID first = maintenanceOrder(), second = maintenanceOrder(), part = part(fixture.tenantA, "1");
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (UUID order : List.of(first, second)) futures.add(pool.submit(() -> {
                start.await();
                return add(order, item(UUID.randomUUID(), part, 0, "1")).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            var results = new ArrayList<Integer>();
            for (var result : futures) results.add(result.get(15, java.util.concurrent.TimeUnit.SECONDS));
            Collections.sort(results);
            assertEquals(List.of(201, 422), results);
            balance(part, "0");
            inTenant(fixture.tenantA, () -> {
                assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_items WHERE part_id = ?", Integer.class, part));
                assertEquals(1, admin.queryForObject("SELECT count(*) FROM inventory_movements WHERE part_id = ? AND type = 'EXIT'", Integer.class, part));
                assertEquals(1, admin.queryForObject("SELECT sum(version) FROM work_orders WHERE id IN (?, ?)", Integer.class, first, second));
            });
        } finally { pool.shutdownNow(); }
    }
    @Test void servicesUpdateReadinessAndRemovalClearsTheEvidence() throws Exception {
        UUID order = maintenanceOrder(), item = UUID.randomUUID();
        transition(order, "PRONTA", 0).andExpect(status().isConflict());
        add(order, item(item, null, 0, "1")).andExpect(status().isCreated());
        remove(order, item, 1).andExpect(status().isOk());
        transition(order, "PRONTA", 2).andExpect(status().isConflict());
        add(order, item(UUID.randomUUID(), null, 2, "1")).andExpect(status().isCreated());
        transition(order, "PRONTA", 3).andExpect(status().isOk());
        add(order, item(UUID.randomUUID(), null, 4, "1")).andExpect(status().isConflict());
    }
    @Test void enforcesValidationVersionTenantAndRoles() throws Exception {
        UUID order = maintenanceOrder(), foreign = part(fixture.tenantB, "1");
        add(order, item(UUID.randomUUID(), foreign, 0, "1")).andExpect(status().isNotFound());
        add(order, item(UUID.randomUUID(), null, 0, "0")).andExpect(status().isBadRequest());
        add(order, item(UUID.randomUUID(), null, 0, "0.0001")).andExpect(status().isBadRequest());
        add(order, item(UUID.randomUUID(), null, 1, "1")).andExpect(status().isConflict());
        role("ATTENDANT");
        mvc.perform(get(url + "/" + order + "/items").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        add(order, item(UUID.randomUUID(), null, 0, "1")).andExpect(status().isForbidden());
        role("TECHNICIAN");
        add(order, item(UUID.randomUUID(), null, 0, "1")).andExpect(status().isCreated());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_transition_facts SET technician_id = NULL WHERE work_order_id = ?", order));
        mvc.perform(get(url + "/" + order + "/items").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        add(order, item(UUID.randomUUID(), null, 1, "1")).andExpect(status().isForbidden());
    }
    @Test void honorsExplicitNegativeStockSetting() throws Exception {
        UUID order = maintenanceOrder(), part = part(fixture.tenantA, "0");
        inTenant(fixture.tenantA, () -> admin.update("INSERT INTO tenant_settings(tenant_id, allow_negative_stock) VALUES (?, true) ON CONFLICT (tenant_id) DO UPDATE SET allow_negative_stock = true", fixture.tenantA));
        add(order, item(UUID.randomUUID(), part, 0, "1")).andExpect(status().isCreated());
        balance(part, "-1");
    }
    @Test void t6FailureAfterDebitRollsBackItemStockMovementVersionAuditAndEvent() throws Exception {
        UUID order = maintenanceOrder(), part = part(fixture.tenantA, "1"), item = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated downstream failure")).when((br.com.fixflow.shared.application.audit.AuditService) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit)).record(
                org.mockito.ArgumentMatchers.eq(fixture.userId), org.mockito.ArgumentMatchers.eq("WORK_ORDER_ITEM_ADDED"),
                org.mockito.ArgumentMatchers.eq("WORK_ORDER"), org.mockito.ArgumentMatchers.eq(order), org.mockito.ArgumentMatchers.anyString());
        add(order, item(item, part, 0, "1")).andExpect(status().isInternalServerError());
        balance(part, "1");
        inTenant(fixture.tenantA, () -> {
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM work_order_items WHERE id = ?", Integer.class, item));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM inventory_movements WHERE item_id = ?", Integer.class, item));
            assertEquals(0, admin.queryForObject("SELECT version FROM work_orders WHERE id = ?", Integer.class, order));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, order));
        });
    }
    @Test void failedRemovalDoesNotRestoreStockOrRemoveItem() throws Exception {
        UUID order = maintenanceOrder(), part = part(fixture.tenantA, "1"), item = UUID.randomUUID();
        add(order, item(item, part, 0, "1")).andExpect(status().isCreated());
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated downstream failure")).when((br.com.fixflow.shared.application.audit.AuditService) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit)).record(
                org.mockito.ArgumentMatchers.eq(fixture.userId), org.mockito.ArgumentMatchers.eq("WORK_ORDER_ITEM_REMOVED"),
                org.mockito.ArgumentMatchers.eq("WORK_ORDER"), org.mockito.ArgumentMatchers.eq(order), org.mockito.ArgumentMatchers.anyString());
        remove(order, item, 1).andExpect(status().isInternalServerError());
        balance(part, "0");
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_items WHERE id = ? AND removed_at IS NULL", Integer.class, item));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM inventory_movements WHERE item_id = ? AND type = 'RETURN'", Integer.class, item));
            assertEquals(1, admin.queryForObject("SELECT version FROM work_orders WHERE id = ?", Integer.class, order));
        });
    }
    @Test void catalogNormalizesSkuAndEnforcesTenantUniquenessAndOwnerWrites() throws Exception {
        var body = Map.of("sku", " tela-1 ", "name", "Tela", "unit", "UN", "cost", 10, "salePrice", 20, "minQuantity", 1);
        mvc.perform(post("/api/v1/parts").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sku").value("TELA-1")).andExpect(jsonPath("$.quantityOnHand").value(0));
        mvc.perform(post("/api/v1/parts").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
        role("ATTENDANT");
        mvc.perform(get("/api/v1/parts").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/v1/parts").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test void partCrudPreservesBalanceAndSoftDeletesWithVersionControl() throws Exception {
        UUID id = part(fixture.tenantA, "5");
        String path = "/api/v1/parts/" + id;
        var body = new HashMap<String, Object>(Map.of("sku", " NEW-SKU ", "name", "Nova peça", "unit", "UN",
                "cost", 12, "salePrice", 30, "minQuantity", 2, "version", 0));
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        body.put("quantityOnHand", 100);
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.remove("quantityOnHand");
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.sku").value("NEW-SKU"));
        balance(id, "5");
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isConflict());
        mvc.perform(delete(path).param("version", "0").header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
        mvc.perform(delete(path).param("version", "1").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/parts").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.length()").value(0));
        balance(id, "5");
        inTenant(fixture.tenantA, () -> assertEquals(false, admin.queryForObject("SELECT active FROM parts WHERE id = ?", Boolean.class, id)));
        mvc.perform(post("/api/v1/parts").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isConflict());
    }
    @Test void partCrudIsolatesTenantsAndRejectsInitialBalance() throws Exception {
        UUID foreign = part(fixture.tenantB, "2");
        var body = new HashMap<String, Object>(Map.of("sku", "NEW", "name", "Tela", "unit", "UN",
                "cost", 10, "salePrice", 20, "minQuantity", 1, "version", 0));
        String path = "/api/v1/parts/" + foreign;
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isNotFound());
        mvc.perform(delete(path).param("version", "0").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        body.put("quantity", 3);
        mvc.perform(post("/api/v1/parts").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.remove("quantity");
        role("TECHNICIAN");
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isForbidden());
        mvc.perform(delete(path).param("version", "0").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

}
