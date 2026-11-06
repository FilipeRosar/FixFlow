package br.com.fixflow.modules.quotes;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** T2/T3 plus inherited T10 and commercial regression cases. */
class PublicQuoteDecisionT2T3IntegrationTest extends PublicQuoteT10IntegrationTest {
    String hash(Link link) throws Exception {
        return mapper.readTree(view(link.token()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("contentHash").asText();
    }
    org.springframework.test.web.servlet.ResultActions decide(Link link, String action, String contentHash, String reason, String key) throws Exception {
        var body = new HashMap<String, Object>(); body.put("confirmed", true); body.put("contentHash", contentHash); body.put("reason", reason);
        return mvc.perform(post(publicUrl + link.token() + "/" + action).header("Idempotency-Key", key).header("Origin", "http://localhost:4200")
                .header("User-Agent", "FixFlow-test-browser/1.0").header("X-Forwarded-For", "1.2.3.4")
                .contentType("application/json").content(mapper.writeValueAsString(body)).with(request -> { request.setRemoteAddr(ip); return request; }));
    }
    @Test void t2ApprovalCapturesExactContentAndEvidenceAndReplaysWithoutAnotherDecision() throws Exception {
        Link link = link(); String hash = hash(link), key = UUID.randomUUID().toString();
        String first = decide(link, "approve", hash, null, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED")).andExpect(jsonPath("$.workOrderStatus").value("APROVADA"))
                .andExpect(jsonPath("$.contentHash").value(hash)).andExpect(jsonPath("$.decidedAt").exists()).andReturn().getResponse().getContentAsString();
        assertEquals(first, decide(link, "approve", hash, null, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        decide(link, "reject", hash, "Outra decisão", key).andExpect(status().isConflict());
        decide(link, "approve", hash, null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> {
            var evidence = admin.queryForMap("SELECT * FROM quote_decisions WHERE quote_id = ?", link.quote());
            assertEquals("APPROVED", evidence.get("decision")); assertEquals("PUBLIC_LINK", evidence.get("channel"));
            assertEquals(ip, evidence.get("ip_address").toString()); assertEquals("FixFlow-test-browser/1.0", evidence.get("user_agent"));
            assertEquals(hash, evidence.get("quote_content_hash")); assertEquals(1, ((Number) evidence.get("quote_version")).intValue());
            assertNotNull(evidence.get("decided_at"));
            assertTrue(admin.queryForObject("SELECT quote_content_hash = encode(sha256(convert_to(quote_content::text, 'UTF8')), 'hex') FROM quote_decisions WHERE quote_id = ?", Boolean.class, link.quote()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteApproved'", Integer.class, link.order()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ? AND source = 'CUSTOMER' AND changed_by IS NULL", Integer.class, link.order()));
            assertEquals("APPROVED", admin.queryForObject("SELECT quote_status FROM work_order_transition_facts WHERE work_order_id = ?", String.class, link.order()));
            assertTrue(admin.queryForObject("SELECT read_until = d.decided_at + interval '90 days' FROM quote_access_tokens a JOIN quote_decisions d ON d.tenant_id = a.tenant_id AND d.quote_id = a.quote_id WHERE a.quote_id = ?", Boolean.class, link.quote()));
        });
        view(link.token()).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true)).andExpect(jsonPath("$.decision.decision").value("APPROVED"))
                .andExpect(jsonPath("$.contentHash").value(hash)).andExpect(jsonPath("$.decision.ipAddress").doesNotExist()).andExpect(jsonPath("$.decision.userAgent").doesNotExist());
        transition(link.order(), "EM_MANUTENCAO", 7).andExpect(status().isOk());
    }
    @Test void t3RejectionIsFinalForTheVersionAndCannotStartMaintenance() throws Exception {
        Link link = link(); String hash = hash(link);
        decide(link, "reject", hash, "Não concordo com o valor", UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.workOrderStatus").value("ORCAMENTO_REJEITADO"));
        transition(link.order(), "EM_MANUTENCAO", 7).andExpect(status().isConflict());
        decide(link, "approve", hash, null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        view(link.token()).andExpect(jsonPath("$.decision.reason").value("Não concordo com o valor")).andExpect(jsonPath("$.readOnly").value(true));
        inTenant(fixture.tenantA, () -> {
            assertEquals("ORCAMENTO_REJEITADO", admin.queryForObject("SELECT status FROM work_orders WHERE id = ?", String.class, link.order()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteRejected'", Integer.class, link.order()));
        });
    }
    @Test void simultaneousOppositeDecisionsProduceOneWinnerAndOneConflict() throws Exception {
        Link link = link(); String hash = hash(link);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approve = pool.submit(() -> { start.await(); return decide(link, "approve", hash, null, UUID.randomUUID().toString()).andReturn().getResponse().getStatus(); });
            var reject = pool.submit(() -> { start.await(); return decide(link, "reject", hash, "Prefiro não reparar", UUID.randomUUID().toString()).andReturn().getResponse().getStatus(); });
            start.countDown(); var codes = new ArrayList<>(List.of(approve.get(), reject.get())); Collections.sort(codes); assertEquals(List.of(200, 409), codes);
        }
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type IN ('QuoteApproved', 'QuoteRejected')", Integer.class, link.order()));
        });
    }
    @Test void concurrentSameKeyReturnsIdenticalReceipts() throws Exception {
        Link link = link(); String hash = hash(link), key = UUID.randomUUID().toString(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return decide(link, "approve", hash, null, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); });
            var second = pool.submit(() -> { start.await(); return decide(link, "approve", hash, null, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); });
            start.countDown(); assertEquals(first.get(), second.get());
        }
    }
    @Test void changedDisplayedContentRequiresReloadAndTheConfirmedSnapshotRemainsStable() throws Exception {
        Link link = link(); String oldHash = hash(link);
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenants SET name = 'Nome novo da oficina' WHERE id = ?", fixture.tenantA));
        decide(link, "approve", oldHash, null, UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CONTENT_CHANGED"));
        String currentHash = hash(link); assertNotEquals(oldHash, currentHash);
        decide(link, "approve", currentHash, null, UUID.randomUUID().toString()).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenants SET name = 'Nome posterior' WHERE id = ?", fixture.tenantA));
        view(link.token()).andExpect(jsonPath("$.companyName").value("Nome novo da oficina")).andExpect(jsonPath("$.contentHash").value(currentHash));
    }
    @Test void expiredRevokedAndUnknownTokensUseTheSame404ForDecisions() throws Exception {
        Link link = link(); String hash = hash(link);
        Link unknown = new Link(link.order(), link.quote(), "Z".repeat(43));
        String expected = decide(unknown, "approve", hash, null, UUID.randomUUID().toString()).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        inTenant(fixture.tenantA, () -> admin.update("UPDATE quote_access_tokens SET expires_at = clock_timestamp() - interval '1 second' WHERE quote_id = ?", link.quote()));
        assertEquals(expected, decide(link, "approve", hash, null, UUID.randomUUID().toString()).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE quote_access_tokens SET expires_at = clock_timestamp() + interval '1 day', revoked_at = clock_timestamp() WHERE quote_id = ?", link.quote()));
        assertEquals(expected, decide(link, "reject", hash, null, UUID.randomUUID().toString()).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        inTenant(fixture.tenantA, () -> assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote())));
    }
    @Test void rejectsUnconfirmedCrossOriginFormSubmissionsAndGetDecisions() throws Exception {
        Link link = link(); String hash = hash(link), url = publicUrl + link.token() + "/approve";
        mvc.perform(get(url).with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isNotFound());
        mvc.perform(post(url).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("confirmed", false, "contentHash", hash))).with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType("application/json").content(mapper.writeValueAsString(Map.of("confirmed", true, "contentHash", hash)))
                .with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Origin", "https://untrusted.example").contentType("application/json").content("{}")
                .with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isForbidden());
        mvc.perform(post(url).contentType("application/x-www-form-urlencoded").content("confirmed=true")
                .with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(url).contentType("application/json").content("{invalid")
                .with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isBadRequest());
        inTenant(fixture.tenantA, () -> assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote())));
    }
    @Test void sideEffectFailureRollsBackTheEntireDecisionAndAllowsRetry() throws Exception {
        Link link = link(); String hash = hash(link), key = UUID.randomUUID().toString();
        String constraint = "fail_public_" + link.order().toString().replace("-", "");
        admin.execute("ALTER TABLE outbox_event ADD CONSTRAINT " + constraint + " CHECK (aggregate_id <> '" + link.order() + "'::uuid OR event_type NOT IN ('QuoteApproved','QuoteRejected')) NOT VALID");
        try { String failure = decide(link, "approve", hash, null, key).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString(); assertFalse(failure.contains(link.token())); assertFalse(failure.contains("instance")); }
        finally { admin.execute("ALTER TABLE outbox_event DROP CONSTRAINT " + constraint); }
        view(link.token()).andExpect(jsonPath("$.readOnly").value(false));
        inTenant(fixture.tenantA, () -> {
            assertEquals("AGUARDANDO_APROVACAO", admin.queryForObject("SELECT status FROM work_orders WHERE id = ?", String.class, link.order()));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote()));
        });
        decide(link, "approve", hash, null, key).andExpect(status().isOk());
    }
    @Test void rejectsForgedHashesAndProtectsStoredEvidence() throws Exception {
        Link link = link(); String hash = hash(link);
        decide(link, "approve", "0".repeat(64), null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        decide(link, "reject", hash, "Sem reparo", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> inTenant(fixture.tenantA, () -> admin.update("UPDATE quote_decisions SET user_agent = 'changed' WHERE quote_id = ?", link.quote())));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> runtime.update("DELETE FROM quote_decisions WHERE quote_id = ?", link.quote()));
        assertEquals(0, runtime.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote()));
    }
}
