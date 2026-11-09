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

@SpringBootTest(properties = {"fixflow.asaas.enabled=true", "fixflow.asaas.api-key=test-api-key", "fixflow.asaas.webhook-token=01234567890123456789012345678901"})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class AsaasPixIntegrationTest {
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
    static final String WEBHOOK_TOKEN = "01234567890123456789012345678901";
    static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    static final java.util.concurrent.ConcurrentHashMap<String, tools.jackson.databind.node.ObjectNode> remote = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicInteger posts = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    static volatile boolean failAfterCreate;
    static final com.sun.net.httpserver.HttpServer server = server();
    static com.sun.net.httpserver.HttpServer server() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v3", exchange -> {
                requests.incrementAndGet();
                if (!"test-api-key".equals(exchange.getRequestHeaders().getFirst("access_token"))) { exchange.sendResponseHeaders(401, -1); exchange.close(); return; }
                String path = exchange.getRequestURI().getPath();
                String response = "{}"; int status = 200;
                if (path.equals("/v3/customers/cus_test")) response = "{\"id\":\"cus_test\",\"cpfCnpj\":\"12345678901\"}";
                else if (path.equals("/v3/customers/cus_wrong")) response = "{\"id\":\"cus_wrong\",\"cpfCnpj\":\"00000000000\"}";
                else if (path.equals("/v3/payments") && exchange.getRequestMethod().equals("POST")) {
                    var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(exchange.getRequestBody().readAllBytes());
                    String id = "pay_test" + posts.incrementAndGet(); body.put("id", id); body.put("status", "PENDING"); remote.put(id, body);
                    response = body.toString();
                    if (failAfterCreate) { status = 500; response = "{}"; }
                } else if (path.equals("/v3/payments")) {
                    String query = exchange.getRequestURI().getQuery();
                    var result = remote.values().stream().filter(n -> query.contains(n.path("externalReference").asText())).toList();
                    response = JSON.writeValueAsString(Map.of("data", result, "hasMore", false));
                } else if (path.endsWith("/pixQrCode")) response = "{\"encodedImage\":\"aW1hZ2U=\",\"payload\":\"000201PIX\",\"expirationDate\":\"2030-12-31 23:59:59\"}";
                else if (path.startsWith("/v3/payments/")) {
                    var payment = remote.get(path.substring("/v3/payments/".length()));
                    if (payment == null) status = 404; else response = payment.toString();
                } else status = 404;
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    @org.springframework.test.context.DynamicPropertySource
    static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("fixflow.asaas.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v3");
    }
    @BeforeEach void resetGateway() { remote.clear(); posts.set(0); requests.set(0); failAfterCreate = false; }
    @AfterAll static void stopGateway() { server.stop(0); }
    UUID pixOrder() throws Exception {
        UUID id = order(); approve(id);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE customers SET document = '12345678901' WHERE tenant_id = ?", fixture.tenantA));
        return id;
    }
    org.springframework.test.web.servlet.ResultActions createPix(UUID order, String key, int amount, String customer) throws Exception {
        return mvc.perform(post(url + "/" + order + "/pix-charges").header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("amount", amount, "asaasCustomerId", customer,
                        "dueDate", java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).plusDays(1).toString()))));
    }
    org.springframework.test.web.servlet.ResultActions webhook(String eventId, String remoteId, String secret) throws Exception {
        var request = post("/webhooks/asaas").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("id", eventId, "event", "PAYMENT_RECEIVED", "payment", Map.of("id", remoteId, "value", 999999, "tenantId", fixture.tenantB))));
        if (secret != null) request.header("asaas-access-token", secret);
        return mvc.perform(request);
    }
    String create(UUID order, String key, int amount) throws Exception {
        var result = createPix(order, key, amount, "cus_test").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payload").value("000201PIX")).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("gatewayRef").asText();
    }
    @Test void createsPixAndReconcilesActualAmountOnceAcrossRepeatedEvents() throws Exception {
        UUID order = pixOrder(); String id = create(order, "pix", 100);
        financial(order, "UNPAID", false);
        createPix(order, "pix", 100, "cus_test").andExpect(status().isOk()); assertEquals(1, posts.get());
        createPix(order, "pix", 99, "cus_test").andExpect(status().isConflict());
        webhook("evt_pending", id, WEBHOOK_TOKEN).andExpect(status().isConflict());
        remote.get(id).put("status", "RECEIVED").put("paymentDate", "2026-11-08");
        webhook("evt_one", id, WEBHOOK_TOKEN).andExpect(status().isOk());
        webhook("evt_one", id, WEBHOOK_TOKEN).andExpect(status().isOk());
        webhook("evt_two", id, WEBHOOK_TOKEN).andExpect(status().isOk());
        financial(order, "PAID", true);
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM payments WHERE work_order_id = ?", Integer.class, order));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'PAYMENT_REGISTERED'", Integer.class, order));
        });
        var payments = mvc.perform(get(url + "/" + order + "/payments").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        UUID payment = UUID.fromString(mapper.readTree(payments.getResponse().getContentAsString()).get(0).get("id").asText());
        mvc.perform(post("/api/v1/payments/" + payment + "/void").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"reason\":\"Correção\"}"))
                .andExpect(status().isOk());
        webhook("evt_aftervoid", id, WEBHOOK_TOKEN).andExpect(status().isOk()); financial(order, "UNPAID", false);
    }
    @Test void rejectsMissingOrForgedTokenBeforeCallingAsaasAndDoesNotTrustPayload() throws Exception {
        UUID order = pixOrder(); String id = create(order, "pix", 100); int before = requests.get();
        webhook("evt_forged", id, "incorrect").andExpect(status().isUnauthorized());
        webhook("evt_missing", id, null).andExpect(status().isUnauthorized()); assertEquals(before, requests.get());
        remote.get(id).put("status", "RECEIVED").put("value", 1);
        webhook("evt_mismatch", id, WEBHOOK_TOKEN).andExpect(status().isConflict()); financial(order, "UNPAID", false);
    }
    @Test void recoversAmbiguousCreationByReferenceWithoutPostingAgain() throws Exception {
        UUID order = pixOrder(); failAfterCreate = true;
        createPix(order, "uncertain", 100, "cus_test").andExpect(status().isBadGateway()); assertEquals(1, posts.get());
        failAfterCreate = false; createPix(order, "uncertain", 100, "cus_test").andExpect(status().isOk()).andExpect(jsonPath("$.payload").value("000201PIX"));
        assertEquals(1, posts.get());
    }
    @Test void rejectsWrongPayerRolesAndCrossTenantOrder() throws Exception {
        UUID order = pixOrder(); createPix(order, "wrongpayer", 100, "cus_wrong").andExpect(status().isConflict()); assertEquals(0, posts.get());
        createPix(UUID.randomUUID(), "missing", 100, "cus_test").andExpect(status().isNotFound());
        UUID foreignCustomer = customer(fixture.tenantB, "Outra empresa"); UUID foreignOrder = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, status) VALUES (?, ?, ?, 'RECEBIDA')", foreignOrder, fixture.tenantB, foreignCustomer));
        createPix(foreignOrder, "foreign", 100, "cus_test").andExpect(status().isNotFound());
        mvc.perform(get(url + "/" + foreignOrder + "/pix-charges").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId)); token = login();
        createPix(order, "forbidden", 100, "cus_test").andExpect(status().isForbidden());
    }
    @Test void concurrentCreationAndWebhooksDoNotDuplicateChargesOrPayments() throws Exception {
        UUID order = pixOrder();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> request = () -> { start.await(); return createPix(order, "concurrent", 100, "cus_test").andReturn().getResponse().getStatus(); };
            var first = pool.submit(request); var second = pool.submit(request); start.countDown();
            int a = first.get(20, java.util.concurrent.TimeUnit.SECONDS); int b = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(Set.of(200, 503).contains(a)); assertTrue(Set.of(200, 503).contains(b)); assertTrue(a == 200 || b == 200);
        }
        assertEquals(1, posts.get());
        String id = remote.keySet().iterator().next(); remote.get(id).put("status", "RECEIVED");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return webhook("evt_parallelA", id, WEBHOOK_TOKEN).andReturn().getResponse().getStatus(); });
            var second = pool.submit(() -> { start.await(); return webhook("evt_parallelB", id, WEBHOOK_TOKEN).andReturn().getResponse().getStatus(); }); start.countDown();
            assertEquals(200, first.get(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(200, second.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        financial(order, "PAID", true);
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM payments WHERE work_order_id = ?", Integer.class, order)));
    }
    @Test void auditFailureRollsBackInboxLedgerAndStatusForRetry() throws Exception {
        UUID order = pixOrder(); String id = create(order, "atomic", 100); remote.get(id).put("status", "RECEIVED");
        String constraint = "reject_asaas_audit_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("ALTER TABLE public.audit_log ADD CONSTRAINT " + constraint + " CHECK (resource_id <> '" + order + "'::uuid OR details->>'gateway' IS DISTINCT FROM 'ASAAS') NOT VALID");
        try {
            int status = webhook("evt_rollback", id, WEBHOOK_TOKEN).andReturn().getResponse().getStatus();
            assertTrue(status >= 400);
        } finally { admin.execute("ALTER TABLE public.audit_log DROP CONSTRAINT " + constraint); }
        financial(order, "UNPAID", false);
        inTenant(fixture.tenantA, () -> {
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM payments WHERE work_order_id = ?", Integer.class, order));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM asaas_webhook_events WHERE id = 'evt_rollback'", Integer.class));
            assertEquals("PENDING", admin.queryForObject("SELECT status FROM pix_charges WHERE work_order_id = ?", String.class, order));
        });
        webhook("evt_rollback", id, WEBHOOK_TOKEN).andExpect(status().isOk()); financial(order, "PAID", true);
    }
    @Test void refreshRecoversAnExistingIntentAndWebhookBeforeQrFinalization() throws Exception {
        UUID order = pixOrder(); failAfterCreate = true;
        createPix(order, "early", 100, "cus_test").andExpect(status().isBadGateway()); failAfterCreate = false;
        String id = remote.keySet().iterator().next(); remote.get(id).put("status", "RECEIVED");
        webhook("evt_early", id, WEBHOOK_TOKEN).andExpect(status().isOk()); financial(order, "PAID", true);
        var list = mvc.perform(get(url + "/" + order + "/pix-charges").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        String local = mapper.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
        mvc.perform(post(url + "/" + order + "/pix-charges/" + local + "/refresh").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
        assertEquals(1, posts.get());
    }

}
