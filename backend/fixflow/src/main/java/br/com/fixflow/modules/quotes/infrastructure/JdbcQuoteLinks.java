package br.com.fixflow.modules.quotes.infrastructure;

import br.com.fixflow.modules.quotes.application.QuoteLinks;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.modules.workorders.domain.WorkOrderVersionConflictException;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.application.outbox.OutboxService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class JdbcQuoteLinks implements QuoteLinks {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final QuoteLinkEmail emails;
    public JdbcQuoteLinks(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity,
                          AuditService audit, OutboxService outbox, ObjectMapper mapper, QuoteLinkEmail emails) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.audit = audit;
        this.outbox = outbox; this.mapper = mapper; this.emails = emails;
    }
    @Override public Receipt revoke(UUID order, UUID quote, int version, String key) { return manage(order, quote, version, key, false); }
    @Override public Receipt resend(UUID order, UUID quote, int version, String key) { return manage(order, quote, version, key, true); }
    private Receipt manage(UUID order, UUID quote, int version, String key, boolean resend) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma Idempotency-Key válida com 8 a 128 caracteres.");
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new org.springframework.security.authentication.InsufficientAuthenticationException("Selecione uma empresa.");
        return tenants.execute(tenant, member -> {
            if (!RolePermissions.forRole(member.role().name()).contains("quote:send")) throw new AccessDeniedException("Gestão do link de orçamento não autorizada.");
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.quotes WHERE tenant_id = ? AND work_order_id = ? AND id = ?)", Boolean.class, tenant, order, quote)))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Orçamento não encontrado nesta OS.");
            String keyHash = QuoteTokenSecrets.hash(key), requestHash = QuoteTokenSecrets.hash("quote-link:" + (resend ? "RESEND" : "REVOKE") + ":" + order + ":" + quote + ":" + version);
            // Same key-lock-before-OS-lock ordering as the initial send.
            jdbc.update("INSERT INTO public.quote_send_requests(tenant_id, key_hash, request_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", tenant, keyHash, requestHash);
            var saved = jdbc.queryForMap("SELECT *, expires_at <= clock_timestamp() AS expired FROM public.quote_send_requests WHERE tenant_id = ? AND key_hash = ? FOR UPDATE", tenant, keyHash);
            if (Boolean.TRUE.equals(saved.get("expired"))) jdbc.update("UPDATE public.quote_send_requests SET request_hash = ?, response = NULL, expires_at = clock_timestamp() + interval '24 hours' WHERE tenant_id = ? AND key_hash = ?", requestHash, tenant, keyHash);
            else {
                if (!requestHash.equals(saved.get("request_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key já utilizada com outra requisição.");
                if (saved.get("response") != null) return mapper.readValue(saved.get("response").toString(), Receipt.class);
            }
            var current = jdbc.queryForMap("SELECT status, version FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, order);
            var facts = jdbc.queryForList("SELECT quote_id, quote_status, quote_token_hash FROM public.work_order_transition_facts WHERE tenant_id = ? AND work_order_id = ? FOR UPDATE", tenant, order);
            var proposal = jdbc.queryForMap("SELECT status, valid_until FROM public.quotes WHERE tenant_id = ? AND work_order_id = ? AND id = ? FOR UPDATE", tenant, order, quote);
            if (((Number) current.get("version")).intValue() != version) throw new WorkOrderVersionConflictException();
            String status = (String) proposal.get("status");
            if (facts.isEmpty() || !quote.equals(facts.getFirst().get("quote_id")) || !status.equals(facts.getFirst().get("quote_status")) ||
                    !Set.of("SENT", "APPROVED", "REJECTED").contains(status) || "CANCELADA".equals(current.get("status")) ||
                    status.equals("SENT") && !"AGUARDANDO_APROVACAO".equals(current.get("status")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Gerencie somente o link da proposta vigente e enviada.");
            Instant at = jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
            boolean readOnly = !status.equals("SENT");
            Instant until = ((Timestamp) proposal.get("valid_until")).toInstant();
            if (readOnly) {
                Instant readUntil = jdbc.queryForObject("""
                        SELECT coalesce((SELECT decided_at + interval '90 days' FROM public.quote_decisions WHERE tenant_id = ? AND quote_id = ?),
                            (SELECT max(read_until) FROM public.quote_access_tokens WHERE tenant_id = ? AND quote_id = ?))
                        """, (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), tenant, quote, tenant, quote);
                if (readUntil != null) until = readUntil;
            }
            if (resend && !until.isAfter(at)) throw new ResponseStatusException(HttpStatus.CONFLICT, "O prazo de acesso da proposta expirou. O reenvio não altera sua validade.");
            // Compose before revocation; a recipient/key failure leaves the current link intact.
            var secret = resend ? QuoteTokenSecrets.generate() : null;
            String encrypted = resend ? emails.seal(tenant, order, quote, secret.token(), until) : null;
            int revoked = jdbc.update("UPDATE public.quote_access_tokens SET revoked_at = ? WHERE tenant_id = ? AND quote_id = ? AND revoked_at IS NULL", Timestamp.from(at), tenant, quote);
            boolean changed = resend || revoked > 0 || facts.getFirst().get("quote_token_hash") != null;
            if (resend) {
                jdbc.update("INSERT INTO public.quote_access_tokens(tenant_id, quote_id, token_hash, expires_at, read_until) VALUES (?, ?, ?, ?, ?)", tenant, quote,
                        secret.hash(), proposal.get("valid_until"), Timestamp.from(readOnly ? until : until.plus(90, java.time.temporal.ChronoUnit.DAYS)));
            }
            if (changed) {
                jdbc.update("UPDATE public.work_order_transition_facts SET quote_token_hash = ? WHERE tenant_id = ? AND work_order_id = ?", resend && !readOnly ? secret.hash() : null, tenant, order);
                jdbc.update("UPDATE public.work_orders SET version = version + 1 WHERE tenant_id = ? AND id = ?", tenant, order);
                String action = resend ? "QUOTE_LINK_RESENT" : "QUOTE_LINK_REVOKED";
                audit.record(member.userId(), action, "WORK_ORDER", order, mapper.writeValueAsString(Map.of("quoteId", quote, "readOnly", readOnly, "revokedLinks", revoked)));
                if (resend) outbox.record("WorkOrder", order, "QuoteLinkResent", mapper.writeValueAsString(Map.of("quoteId", quote, "readOnly", readOnly, "encryptedNotification", encrypted)));
            }
            var result = new Receipt(order, quote, version + (changed ? 1 : 0), (String) current.get("status"), resend ? "ACTIVE" : "REVOKED", resend ? "QUEUED" : "NONE", until);
            jdbc.update("UPDATE public.quote_send_requests SET response = ?::jsonb WHERE tenant_id = ? AND key_hash = ?", mapper.writeValueAsString(result), tenant, keyHash);
            return result;
        });
    }
}
