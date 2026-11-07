package br.com.fixflow.modules.quotes;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import br.com.fixflow.shared.infrastructure.outbox.OutboxPublisher;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QuoteLinkRotationIntegrationTest extends PublicQuoteT10IntegrationTest {
    @MockitoBean JavaMailSender mail;
    @Autowired OutboxPublisher publisher;
    org.springframework.test.web.servlet.ResultActions manage(Link link, int version, String action, String key) throws Exception {
        return mvc.perform(post(orders + link.order() + "/quotes/" + link.quote() + "/" + action).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType("application/json").content(mapper.writeValueAsString(Map.of("workOrderVersion", version))));
    }
    String newestToken(Link link) {
        var payload = new AtomicReference<String>();
        inTenant(fixture.tenantA, () -> payload.set(admin.queryForObject("SELECT payload->>'encryptedNotification' FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteLinkResent' ORDER BY created_at DESC, id DESC LIMIT 1", String.class, link.order())));
        return links.open(payload.get(), fixture.tenantA, link.quote()).token();
    }
    @Test void resendInvalidatesTheOldLinkAndIsIdempotentWithoutChangingTheProposal() throws Exception {
        Link link = link(); String key = UUID.randomUUID().toString();
        String contentHash = mapper.readTree(view(link.token()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("contentHash").asText();
        var frozen = new AtomicReference<String>();
        inTenant(fixture.tenantA, () -> frozen.set(admin.queryForObject("SELECT to_jsonb(q)::text FROM quotes q WHERE id = ?", String.class, link.quote())));
        String first = manage(link, 6, "resend-link", key).andExpect(status().isOk()).andExpect(jsonPath("$.linkStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.workOrderVersion").value(7)).andReturn().getResponse().getContentAsString();
        assertEquals(first, manage(link, 6, "resend-link", key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        view(link.token()).andExpect(status().isNotFound()); String fresh = newestToken(link); assertNotEquals(link.token(), fresh);
        view(fresh).andExpect(status().isOk()).andExpect(jsonPath("$.contentHash").value(contentHash));
        manage(link, 6, "revoke-link", key).andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> {
            assertEquals(frozen.get(), admin.queryForObject("SELECT to_jsonb(q)::text FROM quotes q WHERE id = ?", String.class, link.quote()));
            assertEquals(2, admin.queryForObject("SELECT count(*) FROM quote_access_tokens WHERE quote_id = ?", Integer.class, link.quote()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM quote_access_tokens WHERE quote_id = ? AND revoked_at IS NULL", Integer.class, link.quote()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteLinkResent'", Integer.class, link.order()));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action = 'QUOTE_LINK_RESENT'", Integer.class, link.order()));
        });
        mvc.perform(post(publicUrl + fresh + "/approve").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("confirmed", true, "contentHash", contentHash))).with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("APPROVED"));
    }
    @Test void revokeDisablesViewingAndDecisionUntilANewLinkIsSent() throws Exception {
        Link link = link(); String key = UUID.randomUUID().toString();
        String hash = mapper.readTree(view(link.token()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("contentHash").asText();
        String response = manage(link, 6, "revoke-link", key).andExpect(status().isOk()).andExpect(jsonPath("$.linkStatus").value("REVOKED")).andReturn().getResponse().getContentAsString();
        assertEquals(response, manage(link, 6, "revoke-link", key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        view(link.token()).andExpect(status().isNotFound());
        mvc.perform(post(publicUrl + link.token() + "/approve").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("confirmed", true, "contentHash", hash))).with(request -> { request.setRemoteAddr(ip); return request; })).andExpect(status().isNotFound());
        list(link.order()).andExpect(jsonPath("$.quotes[0].linkStatus").value("REVOKED"));
        manage(link, 7, "revoke-link", UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.workOrderVersion").value(7));
        manage(link, 7, "resend-link", UUID.randomUUID().toString()).andExpect(status().isOk()); view(newestToken(link)).andExpect(status().isOk());
        verifyNoInteractions(mail);
    }
    @Test void pendingOldEmailsAreSkippedAndOnlyTheNewTokenIsDeliveredOnce() throws Exception {
        Link link = link(); manage(link, 6, "resend-link", UUID.randomUUID().toString()).andExpect(status().isOk());
        String fresh = newestToken(link); publisher.publishPending();
        var messages = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class); verify(mail).send(messages.capture());
        assertTrue(messages.getValue().getText().contains(fresh)); assertFalse(messages.getValue().getText().contains(link.token()));
        inTenant(fixture.tenantA, () -> {
            assertEquals("SKIPPED", admin.queryForObject("SELECT d.status FROM quote_email_deliveries d JOIN outbox_event e ON e.tenant_id = d.tenant_id AND e.id = d.event_id WHERE e.aggregate_id = ? AND e.event_type = 'QuoteSent'", String.class, link.order()));
            admin.update("UPDATE outbox_event SET status = 'PENDING', next_attempt_at = clock_timestamp() WHERE aggregate_id = ? AND event_type IN ('QuoteSent', 'QuoteLinkResent')", link.order());
        });
        publisher.publishPending(); verify(mail, times(1)).send(any(SimpleMailMessage.class));
    }
    @Test void approvedLinksCanBeRotatedForReadOnlyWithoutReopeningTheDecision() throws Exception {
        Link link = link(); transition(link.order(), "APROVADA", 6).andExpect(status().isOk());
        var decision = new AtomicReference<String>();
        inTenant(fixture.tenantA, () -> decision.set(admin.queryForObject("SELECT to_jsonb(d)::text FROM quote_decisions d WHERE quote_id = ?", String.class, link.quote())));
        manage(link, 7, "resend-link", UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROVADA"));
        view(link.token()).andExpect(status().isNotFound());
        String fresh = newestToken(link); view(fresh).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
        publisher.publishPending(); var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class); verify(mail).send(message.capture());
        assertTrue(message.getValue().getText().contains("somente consulta"));
        inTenant(fixture.tenantA, () -> assertEquals(decision.get(), admin.queryForObject("SELECT to_jsonb(d)::text FROM quote_decisions d WHERE quote_id = ?", String.class, link.quote())));
        manage(link, 8, "revoke-link", UUID.randomUUID().toString()).andExpect(status().isOk()); view(fresh).andExpect(status().isNotFound());
        manage(link, 9, "resend-link", UUID.randomUUID().toString()).andExpect(status().isOk()); view(newestToken(link)).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
    }
    @Test void invalidRecipientOrAuditFailurePreservesTheCurrentLinkAndAllowsRetry() throws Exception {
        Link link = link(); String key = UUID.randomUUID().toString();
        inTenant(fixture.tenantA, () -> admin.update("DELETE FROM customer_contacts WHERE tenant_id = ?", fixture.tenantA));
        manage(link, 6, "resend-link", key).andExpect(status().isUnprocessableContent()); view(link.token()).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> admin.update("INSERT INTO customer_contacts(tenant_id, customer_id, type, value, position) SELECT tenant_id, customer_id, 'EMAIL', 'ana@example.test', 0 FROM work_orders WHERE id = ?", link.order()));
        br.com.fixflow.shared.application.audit.AuditService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("audit unavailable")).when(target).record(any(), eq("QUOTE_LINK_RESENT"), any(), eq(link.order()), any());
        try { manage(link, 6, "resend-link", key).andExpect(status().isInternalServerError()); } finally { reset(target); }
        view(link.token()).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM quote_access_tokens WHERE quote_id = ?", Integer.class, link.quote())));
        manage(link, 6, "resend-link", key).andExpect(status().isOk()); view(link.token()).andExpect(status().isNotFound());
    }
    @Test void permissionsVersionAndReferenceChecksCannotBeBypassed() throws Exception {
        Link link = link(); role("TECHNICIAN"); manage(link, 6, "resend-link", UUID.randomUUID().toString()).andExpect(status().isForbidden());
        role("ATTENDANT"); manage(link, 5, "revoke-link", UUID.randomUUID().toString()).andExpect(status().isConflict());
        manage(link, 6, "revoke-link", "bad key").andExpect(status().isBadRequest());
        manage(new Link(UUID.randomUUID(), link.quote(), link.token()), 6, "resend-link", UUID.randomUUID().toString()).andExpect(status().isNotFound());
        manage(link, 6, "resend-link", UUID.randomUUID().toString()).andExpect(status().isOk());
    }
    @Test void concurrentRotationsProduceOneNewLink() throws Exception {
        Link link = link(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return manage(link, 6, "resend-link", UUID.randomUUID().toString()).andReturn().getResponse().getStatus(); });
            var second = pool.submit(() -> { start.await(); return manage(link, 6, "resend-link", UUID.randomUUID().toString()).andReturn().getResponse().getStatus(); });
            start.countDown(); var statuses = new ArrayList<>(List.of(first.get(), second.get())); Collections.sort(statuses); assertEquals(List.of(200, 409), statuses);
        }
        view(link.token()).andExpect(status().isNotFound()); view(newestToken(link)).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM quote_access_tokens WHERE quote_id = ? AND revoked_at IS NULL", Integer.class, link.quote())));
    }
    @Test void smtpFailureRetriesOnlyTheNewLinkWhileTheOldOneStaysRevoked() throws Exception {
        Link link = link(); manage(link, 6, "resend-link", UUID.randomUUID().toString()).andExpect(status().isOk());
        doThrow(new org.springframework.mail.MailSendException("temporary failure")).doNothing().when(mail).send(any(SimpleMailMessage.class));
        publisher.publishPending(); view(link.token()).andExpect(status().isNotFound()); view(newestToken(link)).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE outbox_event SET next_attempt_at = clock_timestamp() WHERE aggregate_id = ? AND event_type = 'QuoteLinkResent'", link.order()));
        publisher.publishPending(); verify(mail, times(2)).send(any(SimpleMailMessage.class));
    }
    @Test void resendCannotExtendAnExpiredProposal() throws Exception {
        UUID order = order(true), quote = quote(save(order, null, body(4)).andExpect(status().isCreated()));
        inTenant(fixture.tenantA, () -> {
            admin.update("UPDATE quotes SET status = 'SENT', sent_at = clock_timestamp() - interval '2 days', valid_until = clock_timestamp() - interval '1 day' WHERE id = ?", quote);
            admin.update("UPDATE work_orders SET status = 'AGUARDANDO_APROVACAO', version = 6 WHERE id = ?", order);
            admin.update("UPDATE work_order_transition_facts SET quote_status = 'SENT', quote_valid_until = clock_timestamp() - interval '1 day' WHERE work_order_id = ?", order);
        });
        manage(new Link(order, quote, "unused"), 6, "resend-link", UUID.randomUUID().toString()).andExpect(status().isConflict());
    }
    @Test void rotationAndPublicDecisionCannotUseTheSameOldTokenConcurrently() throws Exception {
        Link link = link(); String hash = mapper.readTree(view(link.token()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("contentHash").asText();
        var start = new CountDownLatch(1); int rotationStatus, decisionStatus;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var rotation = pool.submit(() -> { start.await(); return manage(link, 6, "resend-link", UUID.randomUUID().toString()).andReturn().getResponse().getStatus(); });
            var decision = pool.submit(() -> { start.await(); return mvc.perform(post(publicUrl + link.token() + "/approve").header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json").content(mapper.writeValueAsString(Map.of("confirmed", true, "contentHash", hash)))
                    .with(request -> { request.setRemoteAddr(ip); return request; })).andReturn().getResponse().getStatus(); });
            start.countDown(); rotationStatus = rotation.get(); decisionStatus = decision.get();
        }
        assertTrue(rotationStatus == 200 && decisionStatus == 404 || rotationStatus == 409 && decisionStatus == 200);
        if (rotationStatus == 200) { view(link.token()).andExpect(status().isNotFound()); view(newestToken(link)).andExpect(status().isOk()); }
        else view(link.token()).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
    }
}
