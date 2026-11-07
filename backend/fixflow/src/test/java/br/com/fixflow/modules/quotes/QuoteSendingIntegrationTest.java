package br.com.fixflow.modules.quotes;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import br.com.fixflow.shared.infrastructure.outbox.OutboxPublisher;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Includes the US-030 regression cases inherited from QuoteIntegrationTest. */
class QuoteSendingIntegrationTest extends QuoteIntegrationTest {
    @MockitoBean JavaMailSender mail;
    @Autowired OutboxPublisher publisher;
    org.springframework.test.web.servlet.ResultActions send(UUID order, UUID quote, int version, String key) throws Exception {
        return mvc.perform(post(orders + order + "/quotes/" + quote + "/send").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType("application/json").content(mapper.writeValueAsString(Map.of("workOrderVersion", version))));
    }
    UUID draft(UUID order) throws Exception { return quote(save(order, null, body(4)).andExpect(status().isCreated())); }
    @Test void sendsOnceAndReplaysOriginalResponseWithoutLeakingTheToken() throws Exception {
        UUID order = order(true), quote = draft(order); String key = UUID.randomUUID().toString();
        var original = send(order, quote, 5, key).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_APROVACAO")).andExpect(jsonPath("$.workOrderVersion").value(6))
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED")).andReturn().getResponse().getContentAsString();
        assertEquals(original, send(order, quote, 5, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        verifyNoInteractions(mail);
        publisher.publishPending();
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(capture.capture());
        var message = capture.getValue();
        assertArrayEquals(new String[]{"ana@example.test"}, message.getTo());
        assertTrue(message.getSubject().contains("OS nº"));
        var matcher = java.util.regex.Pattern.compile("/orcamento#token=([A-Za-z0-9_-]{43})").matcher(message.getText());
        assertTrue(matcher.find()); String rawToken = matcher.group(1);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        inTenant(fixture.tenantA, () -> {
            assertEquals(hash, admin.queryForObject("SELECT token_hash FROM quote_access_tokens WHERE quote_id = ?", String.class, quote));
            assertEquals(hash, admin.queryForObject("SELECT quote_token_hash FROM work_order_transition_facts WHERE work_order_id = ?", String.class, order));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteSent'", Integer.class, order));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ? AND to_status = 'AGUARDANDO_APROVACAO'", Integer.class, order));
            String payload = admin.queryForObject("SELECT payload::text FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteSent'", String.class, order);
            assertFalse(payload.contains(rawToken)); assertFalse(payload.contains("ana@example.test")); assertFalse(payload.contains("publicToken"));
            assertFalse(admin.queryForObject("SELECT string_agg(details::text, ' ') FROM audit_log WHERE resource_id = ?", String.class, order).contains(rawToken));
        });
        assertFalse(original.contains(rawToken));
        // Redelivery of the same outbox event must not submit another email.
        inTenant(fixture.tenantA, () -> admin.update("UPDATE outbox_event SET status = 'PENDING', next_attempt_at = clock_timestamp() WHERE aggregate_id = ? AND event_type = 'QuoteSent'", order));
        publisher.publishPending(); verify(mail, times(1)).send(any(SimpleMailMessage.class));
        transition(order, "APROVADA", 6).andExpect(status().isOk());
        assertEquals(original, send(order, quote, 5, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        send(order, quote, 6, key).andExpect(status().isConflict());
        send(order, quote, 7, UUID.randomUUID().toString()).andExpect(status().isConflict());
    }
    @Test void missingEmailRollsBackEverythingAndTheSameKeyCanBeRetriedAfterCorrection() throws Exception {
        UUID order = order(true), quote = draft(order); String key = UUID.randomUUID().toString();
        inTenant(fixture.tenantA, () -> admin.update("DELETE FROM customer_contacts WHERE tenant_id = ?", fixture.tenantA));
        send(order, quote, 5, key).andExpect(status().isUnprocessableContent());
        list(order).andExpect(jsonPath("$.workOrderVersion").value(5)).andExpect(jsonPath("$.quotes[0].status").value("DRAFT"));
        inTenant(fixture.tenantA, () -> {
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_send_requests WHERE tenant_id = ?", Integer.class, fixture.tenantA));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_access_tokens WHERE quote_id = ?", Integer.class, quote));
            admin.update("INSERT INTO customer_contacts(tenant_id, customer_id, type, value, position) SELECT tenant_id, customer_id, 'EMAIL', 'ana@example.test', 0 FROM work_orders WHERE id = ?", order);
        });
        send(order, quote, 5, key).andExpect(status().isOk());
    }
    @Test void concurrentRequestsWithTheSameKeyHaveTheSameResponseAndOneEvent() throws Exception {
        UUID order = order(true), quote = draft(order); String key = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return send(order, quote, 5, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); });
            var second = pool.submit(() -> { start.await(); return send(order, quote, 5, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); });
            start.countDown(); assertEquals(first.get(), second.get());
        }
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteSent'", Integer.class, order)));
    }
    @Test void smtpFailureRetriesThroughOutboxAndDoesNotUndoTheBusinessOperation() throws Exception {
        UUID order = order(true), quote = draft(order);
        send(order, quote, 5, UUID.randomUUID().toString()).andExpect(status().isOk());
        doThrow(new org.springframework.mail.MailSendException("temporary SMTP failure")).doNothing().when(mail).send(any(SimpleMailMessage.class));
        publisher.publishPending();
        inTenant(fixture.tenantA, () -> {
            assertEquals("AGUARDANDO_APROVACAO", admin.queryForObject("SELECT status FROM work_orders WHERE id = ?", String.class, order));
            assertEquals("PENDING", admin.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteSent'", String.class, order));
            assertEquals(1, admin.queryForObject("SELECT attempts FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteSent'", Integer.class, order));
            admin.update("UPDATE outbox_event SET next_attempt_at = clock_timestamp() WHERE aggregate_id = ? AND event_type = 'QuoteSent'", order);
        });
        publisher.publishPending(); verify(mail, times(2)).send(any(SimpleMailMessage.class));
        inTenant(fixture.tenantA, () -> assertEquals("SENT", admin.queryForObject("SELECT d.status FROM quote_email_deliveries d JOIN outbox_event e ON e.tenant_id = d.tenant_id AND e.id = d.event_id WHERE e.aggregate_id = ?", String.class, order)));
    }
    @Test void revisionRevokesTheOldTokenAndSkipsAnObsoletePendingEmail() throws Exception {
        UUID order = order(true), quote = draft(order);
        send(order, quote, 5, UUID.randomUUID().toString()).andExpect(status().isOk());
        transition(order, "AGUARDANDO_ORCAMENTO", 6).andExpect(status().isOk());
        publisher.publishPending(); verifyNoInteractions(mail);
        inTenant(fixture.tenantA, () -> assertTrue(admin.queryForObject("SELECT revoked_at IS NOT NULL FROM quote_access_tokens WHERE quote_id = ?", Boolean.class, quote)));
        UUID next = quote(save(order, null, body(7)).andExpect(status().isCreated()));
        send(order, quote, 8, UUID.randomUUID().toString()).andExpect(status().isConflict());
        send(order, next, 8, UUID.randomUUID().toString()).andExpect(status().isOk());
    }
    @Test void requiresTheHeaderPermissionAndCorrectOrderAndRejectsTheLegacySendBypass() throws Exception {
        UUID order = order(true), quote = draft(order);
        mvc.perform(post(orders + order + "/quotes/" + quote + "/send").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"workOrderVersion\":5}")).andExpect(status().isBadRequest());
        send(order, quote, 5, "invalid key").andExpect(status().isBadRequest());
        mvc.perform(patch(orders + order + "/status").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"toStatus\":\"AGUARDANDO_APROVACAO\",\"version\":5}")).andExpect(status().isConflict());
        role("TECHNICIAN"); send(order, quote, 5, UUID.randomUUID().toString()).andExpect(status().isForbidden());
        role("ATTENDANT");
        send(UUID.randomUUID(), quote, 5, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        send(order, quote, 5, UUID.randomUUID().toString()).andExpect(status().isOk());
    }
    @Test void expiredKeysDoNotRotateTheLinkOrRequeueAnAlreadySentQuote() throws Exception {
        UUID order = order(true), quote = draft(order); String key = UUID.randomUUID().toString();
        send(order, quote, 5, key).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE quote_send_requests SET expires_at = clock_timestamp() - interval '1 second' WHERE tenant_id = ?", fixture.tenantA));
        send(order, quote, 5, key).andExpect(status().isConflict());
        inTenant(fixture.tenantA, () -> {
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM quote_access_tokens WHERE quote_id = ?", Integer.class, quote));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteSent'", Integer.class, order));
        });
    }
    @Test void sendAuditFailureRollsBackTokenReceiptStateAndNotification() throws Exception {
        UUID order = order(true), quote = draft(order); String key = UUID.randomUUID().toString();
        br.com.fixflow.shared.application.audit.AuditService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("audit unavailable")).when(target).record(any(), eq("WORK_ORDER_STATUS_CHANGED"), any(), eq(order), any());
        try { send(order, quote, 5, key).andExpect(status().isInternalServerError()); }
        finally { reset(target); }
        list(order).andExpect(jsonPath("$.workOrderVersion").value(5)).andExpect(jsonPath("$.quotes[0].status").value("DRAFT"));
        inTenant(fixture.tenantA, () -> {
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_access_tokens WHERE quote_id = ?", Integer.class, quote));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM quote_send_requests WHERE tenant_id = ?", Integer.class, fixture.tenantA));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'QuoteSent'", Integer.class, order));
        });
        send(order, quote, 5, key).andExpect(status().isOk());
    }
}
