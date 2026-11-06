package br.com.fixflow.modules.quotes;

import br.com.fixflow.modules.quotes.application.QuoteLinkProtection;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** T10 plus inherited commercial regression cases. All requests use the runtime role and real PostgreSQL RLS. */
class PublicQuoteT10IntegrationTest extends QuoteIntegrationTest {
    @Autowired QuoteLinkProtection links;
    static final AtomicInteger IPS = new AtomicInteger();
    String ip;
    final String publicUrl = "/public/v1/work-orders/";
    record Link(UUID order, UUID quote, String token) { }
    @BeforeEach void remoteAddress() { ip = "198.51.100." + IPS.incrementAndGet(); }
    Link link() throws Exception {
        UUID order = order(true), quote = quote(save(order, null, body(4)).andExpect(status().isCreated()));
        transition(order, "AGUARDANDO_APROVACAO", 5).andExpect(status().isOk());
        var encrypted = new AtomicReference<String>();
        inTenant(fixture.tenantA, () -> encrypted.set(admin.queryForObject("SELECT payload->>'encryptedNotification' FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteSent'", String.class, order)));
        return new Link(order, quote, links.open(encrypted.get(), fixture.tenantA, quote).token());
    }
    org.springframework.test.web.servlet.ResultActions view(String token) throws Exception {
        return mvc.perform(get(publicUrl + token).with(request -> { request.setRemoteAddr(ip); return request; }));
    }
    @Test void t10InvalidExpiredRevokedAndMalformedTokensReturnTheIdentical404() throws Exception {
        Link link = link();
        String unknown = "A".repeat(42) + "B";
        String expected = view(unknown).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(expected, view("invalid").andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE quote_access_tokens SET expires_at = clock_timestamp() - interval '1 second' WHERE quote_id = ?", link.quote()));
        assertEquals(expected, view(link.token()).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE quote_access_tokens SET expires_at = clock_timestamp() + interval '1 day', revoked_at = clock_timestamp() WHERE quote_id = ?", link.quote()));
        assertEquals(expected, view(link.token()).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        assertFalse(expected.contains(link.token())); assertFalse(expected.contains("instance"));
        assertEquals(expected, mvc.perform(get(publicUrl).with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
    }
    @Test void t10ReturnsOnlyMinimalSnapshotDataWithoutAuthenticationAndCannotSelectAnotherOrder() throws Exception {
        Link first = link(); UUID other = order(true);
        String response = mvc.perform(get(publicUrl + first.token()).param("orderId", other.toString()).header("X-Tenant-Id", fixture.tenantB.toString())
                .header("Authorization", "Bearer invalid-unrelated-token").with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.companyName").value("Integration workshop"))
                .andExpect(jsonPath("$.workOrderNumber").value(1)).andExpect(jsonPath("$.quote.total").value(200))
                .andExpect(jsonPath("$.quote.items[0].description").value("Mão de obra"))
                .andExpect(jsonPath("$.readOnly").value(false)).andReturn().getResponse().getContentAsString();
        var json = mapper.readTree(response);
        assertEquals(Set.of("companyName", "workOrderNumber", "equipmentLabel", "workOrderStatus", "readOnly", "quote"), json.propertyNames());
        assertEquals(Set.of("type", "description", "quantity", "unitPrice"), json.get("quote").get("items").get(0).propertyNames());
        for (String secret : List.of(first.token(), fixture.tenantA.toString(), first.order().toString(), first.quote().toString(), "ana@example.test", "customerId", "unitCost", "reportedIssue", "physicalCondition", "document", "serialNumber", "deliveryReason")) assertFalse(response.contains(secret), secret);
        view(first.token() + "/" + other).andExpect(status().isNotFound());
        mvc.perform(get(orders + first.order())).andExpect(status().isUnauthorized());
        assertEquals(0, runtime.queryForObject("SELECT count(*) FROM work_orders WHERE id = ?", Integer.class, first.order()));
        inTenant(fixture.tenantA, () -> {
            assertEquals("AGUARDANDO_APROVACAO", admin.queryForObject("SELECT status FROM work_orders WHERE id = ?", String.class, first.order()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'PUBLIC_QUOTE_VIEWED' AND actor_id IS NULL", Integer.class, first.order()));
        });
    }
    @Test void t10SecurityHeadersArePresentOnSuccessMissingAndRateLimitedResponses() throws Exception {
        Link link = link();
        for (String token : List.of(link.token(), "wrong")) view(token)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow, noarchive"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
        for (int i = 2; i < 20; i++) view("wrong").andExpect(status().isNotFound());
        view(link.token()).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
    @Test void t10IpLimitIsTwentyPerMinuteAndForwardedHeadersCannotBypassIt() throws Exception {
        for (int i = 0; i < 20; i++) mvc.perform(get(publicUrl + "invalid").header("X-Forwarded-For", "203.0.113." + i).header("Forwarded", "for=203.0.113." + i)
                .with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isNotFound());
        var response = view("another-invalid-token").andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertTrue(Integer.parseInt(response.getHeader("Retry-After")) > 0);
        assertTrue(Integer.parseInt(response.getHeader("Retry-After")) <= 60);
    }
    @Test void t10TokenLimitIsSixtyPerHourAcrossDifferentIps() throws Exception {
        Link link = link();
        for (int i = 0; i < 60; i++) mvc.perform(get(publicUrl + link.token()).with(request -> { request.setRemoteAddr("203.0.113." + IPS.incrementAndGet()); return request; })).andExpect(status().isOk());
        var response = view(link.token()).andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertTrue(Integer.parseInt(response.getHeader("Retry-After")) > 60);
        assertFalse(response.getContentAsString().contains(link.token()));
    }
    @Test void decidedQuotesUseTheirReadWindowAndRemainReadOnly() throws Exception {
        Link link = link(); transition(link.order(), "APROVADA", 6).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE quote_access_tokens SET expires_at = clock_timestamp() - interval '1 day' WHERE quote_id = ?", link.quote()));
        view(link.token()).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true)).andExpect(jsonPath("$.quote.status").value("APPROVED"));
        inTenant(fixture.tenantA, () -> admin.update("UPDATE quote_access_tokens SET read_until = clock_timestamp() - interval '1 second' WHERE quote_id = ?", link.quote()));
        view(link.token()).andExpect(status().isNotFound());
    }
    @Test void revisionsAndSuspendedCompaniesMakeTheLinkUnavailable() throws Exception {
        Link link = link(); fixture.suspendTenant(); view(link.token()).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", fixture.tenantA));
        transition(link.order(), "AGUARDANDO_ORCAMENTO", 6).andExpect(status().isOk());
        view(link.token()).andExpect(status().isNotFound());
    }
    @Test void t10TokensRemainScopedAcrossCompaniesAndTheDatabaseRestoresThePreviousTenant() throws Exception {
        Link first = link();
        UUID customer = UUID.randomUUID(), equipment = UUID.randomUUID(), order = UUID.randomUUID(), quote = UUID.randomUUID();
        byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
        String otherToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(otherToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        inTenant(fixture.tenantB, () -> {
            admin.update("UPDATE tenants SET name = 'Outra oficina' WHERE id = ?", fixture.tenantB);
            admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, 'Cliente privado B')", customer, fixture.tenantB);
            admin.update("INSERT INTO equipment(id, tenant_id, customer_id, category, model) VALUES (?, ?, ?, 'NOTEBOOK', 'Equipamento B')", equipment, fixture.tenantB, customer);
            admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, equipment_id, number, status) VALUES (?, ?, ?, ?, 999, 'AGUARDANDO_APROVACAO')", order, fixture.tenantB, customer, equipment);
            admin.update("INSERT INTO quotes(id, tenant_id, work_order_id, version, discount_type, discount_value, labor_subtotal, parts_subtotal, discount_amount, total, valid_until) VALUES (?, ?, ?, 1, 'FIXED', 0, 100, 0, 0, 100, clock_timestamp() + interval '1 day')", quote, fixture.tenantB, order);
            admin.update("INSERT INTO quote_items(id, tenant_id, quote_id, position, type, description, quantity, unit_price) VALUES (?, ?, ?, 0, 'SERVICE', 'Serviço B', 1, 100)", UUID.randomUUID(), fixture.tenantB, quote);
            admin.update("UPDATE quotes SET status = 'SENT', sent_at = clock_timestamp() WHERE id = ?", quote);
            admin.update("INSERT INTO work_order_transition_facts(tenant_id, work_order_id, quote_id, quote_status, quote_token_hash) VALUES (?, ?, ?, 'SENT', ?)", fixture.tenantB, order, quote, hash);
            admin.update("INSERT INTO quote_access_tokens(tenant_id, quote_id, token_hash, expires_at, read_until) VALUES (?, ?, ?, clock_timestamp() + interval '1 day', clock_timestamp() + interval '90 days')", fixture.tenantB, quote, hash);
        });
        mvc.perform(get(publicUrl + first.token()).param("orderId", order.toString()).param("tenantId", fixture.tenantB.toString())
                .with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isOk()).andExpect(jsonPath("$.workOrderNumber").value(1));
        view(otherToken).andExpect(status().isOk()).andExpect(jsonPath("$.companyName").value("Outra oficina")).andExpect(jsonPath("$.workOrderNumber").value(999));
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(runtime.getDataSource()));
        tx.executeWithoutResult(status -> {
            runtime.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, fixture.tenantA.toString());
            assertTrue(runtime.queryForObject("SELECT public.read_public_quote(?)::text", String.class, hash).contains("Outra oficina"));
            assertEquals(fixture.tenantA.toString(), runtime.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class));
            assertEquals(0, runtime.queryForObject("SELECT count(*) FROM work_orders WHERE id = ?", Integer.class, order));
        });
    }
}
