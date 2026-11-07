package br.com.fixflow.modules.quotes;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Includes the commercial and public T10 regression cases. */
class QuoteManualApprovalIntegrationTest extends PublicQuoteT10IntegrationTest {
    org.springframework.test.web.servlet.ResultActions approve(UUID order, int version, String channel, String reason) throws Exception {
        var body = new HashMap<String, Object>(); body.put("version", version); body.put("channel", channel); body.put("justification", reason);
        return mvc.perform(post(orders + order + "/approve").header("Authorization", "Bearer " + token).header("User-Agent", "Atendimento/1.0")
                .contentType("application/json").content(mapper.writeValueAsString(body)).with(request -> { request.setRemoteAddr(ip); return request; }));
    }
    @Test void ownerRecordsPhoneApprovalWithJustificationSnapshotAndAudit() throws Exception {
        Link link = link(); String reason = "Cliente autorizou por telefone; anotação interna restrita";
        approve(link.order(), 6, "PHONE", reason).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROVADA")).andExpect(jsonPath("$.version").value(7));
        inTenant(fixture.tenantA, () -> {
            var evidence = admin.queryForMap("SELECT * FROM quote_decisions WHERE quote_id = ?", link.quote());
            assertEquals("PHONE", evidence.get("channel")); assertEquals(reason, evidence.get("reason"));
            assertEquals(fixture.userId, evidence.get("decided_by")); assertEquals("Integration identity", evidence.get("decided_by_name"));
            assertEquals(ip, evidence.get("ip_address").toString()); assertEquals("Atendimento/1.0", evidence.get("user_agent"));
            assertTrue(admin.queryForObject("SELECT quote_content_hash = encode(sha256(convert_to(quote_content::text, 'UTF8')), 'hex') FROM quote_decisions WHERE quote_id = ?", Boolean.class, link.quote()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'QUOTE_MANUALLY_APPROVED' AND actor_id = ? AND details->>'reason' = ? AND details->>'approvalChannel' = 'PHONE'", Integer.class, link.order(), fixture.userId, reason));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteApproved'", Integer.class, link.order()));
            assertTrue(admin.queryForObject("SELECT read_until = d.decided_at + interval '90 days' FROM quote_access_tokens a JOIN quote_decisions d ON d.tenant_id = a.tenant_id AND d.quote_id = a.quote_id WHERE a.quote_id = ?", Boolean.class, link.quote()));
        });
        String publicJson = view(link.token()).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.decision.reason").isEmpty()).andReturn().getResponse().getContentAsString();
        assertFalse(publicJson.contains(reason)); assertFalse(publicJson.contains("Integration identity"));
        mvc.perform(get(orders + link.order() + "/timeline").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].details.approvalChannel").value("PHONE")).andExpect(jsonPath("$.content[0].reason").value(reason));
        approve(link.order(), 6, "PHONE", reason).andExpect(status().isConflict());
        approve(link.order(), 7, "PHONE", reason).andExpect(status().isConflict());
    }
    @Test void attendantCanApproveAtCounterAndTheAuthorSnapshotDoesNotChange() throws Exception {
        Link link = link(); role("ATTENDANT");
        approve(link.order(), 6, "COUNTER", "Autorização recebida no balcão").andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> {
            admin.update("UPDATE users SET full_name = 'Nome posterior' WHERE id = ?", fixture.userId);
            assertEquals("Integration identity", admin.queryForObject("SELECT decided_by_name FROM quote_decisions WHERE quote_id = ?", String.class, link.quote()));
            assertEquals("COUNTER", admin.queryForObject("SELECT channel FROM quote_decisions WHERE quote_id = ?", String.class, link.quote()));
        });
    }
    @Test void mandatoryJustificationAndChannelCannotBeBypassed() throws Exception {
        Link link = link();
        for (String reason : Arrays.asList(null, "", "   ", "x".repeat(2001))) approve(link.order(), 6, "PHONE", reason).andExpect(status().isBadRequest());
        for (String channel : Arrays.asList(null, "", "PUBLIC_LINK")) approve(link.order(), 6, channel, "Autorizado").andExpect(status().isBadRequest());
        mvc.perform(patch(orders + link.order() + "/status").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"toStatus\":\"APROVADA\",\"version\":6,\"reason\":\"Tentativa sem canal\"}")).andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> {
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote()));
            assertEquals("AGUARDANDO_APROVACAO", admin.queryForObject("SELECT status FROM work_orders WHERE id = ?", String.class, link.order()));
        });
    }
    @Test void permissionsIsolationAndExpiryAreCheckedServerSide() throws Exception {
        Link link = link(); role("TECHNICIAN"); approve(link.order(), 6, "PHONE", "Autorizado").andExpect(status().isForbidden());
        role("OWNER"); approve(UUID.randomUUID(), 6, "PHONE", "Autorizado").andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_transition_facts SET quote_valid_until = clock_timestamp() - interval '1 second' WHERE work_order_id = ?", link.order()));
        approve(link.order(), 6, "PHONE", "Autorizado").andExpect(status().isConflict());
        UUID draftOrder = order(true); save(draftOrder, null, body(4)).andExpect(status().isCreated());
        approve(draftOrder, 5, "COUNTER", "Autorizado").andExpect(status().isConflict());
    }
    @Test void manualAndPublicApprovalsShareTheSameSingleDecisionLock() throws Exception {
        Link link = link();
        String hash = mapper.readTree(view(link.token()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("contentHash").asText();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var manual = pool.submit(() -> { start.await(); return approve(link.order(), 6, "PHONE", "Cliente autorizou por telefone").andReturn().getResponse().getStatus(); });
            var online = pool.submit(() -> { start.await(); return mvc.perform(post(publicUrl + link.token() + "/approve")
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("confirmed", true, "contentHash", hash))).with(request -> { request.setRemoteAddr(ip); return request; })).andReturn().getResponse().getStatus(); });
            start.countDown(); var statuses = new ArrayList<>(List.of(manual.get(), online.get())); Collections.sort(statuses); assertEquals(List.of(200, 409), statuses);
        }
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteApproved'", Integer.class, link.order()));
        });
    }
    @Test void auditFailureRollsBackTheManualApprovalAndItsEvidence() throws Exception {
        Link link = link();
        br.com.fixflow.shared.application.audit.AuditService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("Audit unavailable")).when(target).record(any(), eq("QUOTE_MANUALLY_APPROVED"), any(), eq(link.order()), any());
        try { approve(link.order(), 6, "COUNTER", "Autorização no balcão").andExpect(status().isInternalServerError()); }
        finally { reset(target); }
        inTenant(fixture.tenantA, () -> {
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_decisions WHERE quote_id = ?", Integer.class, link.quote()));
            assertEquals("SENT", admin.queryForObject("SELECT status FROM quotes WHERE id = ?", String.class, link.quote()));
            assertEquals("AGUARDANDO_APROVACAO", admin.queryForObject("SELECT status FROM work_orders WHERE id = ?", String.class, link.order()));
        });
        view(link.token()).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(false));
        approve(link.order(), 6, "COUNTER", "Autorização no balcão").andExpect(status().isOk());
    }
}
