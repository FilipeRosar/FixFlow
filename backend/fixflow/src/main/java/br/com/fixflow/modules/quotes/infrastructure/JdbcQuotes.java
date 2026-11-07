package br.com.fixflow.modules.quotes.infrastructure;

import br.com.fixflow.modules.quotes.application.*;
import br.com.fixflow.modules.quotes.domain.*;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.modules.tenants.application.FileStorage;
import br.com.fixflow.modules.workorders.domain.WorkOrderVersionConflictException;
import br.com.fixflow.shared.application.tenant.*;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class JdbcQuotes implements Quotes, QuoteWorkflow {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final FileStorage files;
    private final QuoteLinkEmail emails;
    public JdbcQuotes(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity,
                      AuditService audit, ObjectMapper mapper, FileStorage files, QuoteLinkEmail emails) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity;
        this.audit = audit; this.mapper = mapper; this.files = files; this.emails = emails;
    }
    private UUID tenant() {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new org.springframework.security.authentication.InsufficientAuthenticationException("Selecione uma empresa.");
        return tenant;
    }
    private Map<String, Object> authorize(AuthorizedMembership member, UUID order, boolean writing) {
        var permissions = RolePermissions.forRole(member.role().name());
        if (writing ? !permissions.contains("quote:draft") : !permissions.contains("workorder:read") && !permissions.contains("workorder:read_assigned"))
            throw new AccessDeniedException("Acesso ao orçamento não autorizado.");
        var rows = jdbc.queryForList("SELECT status, version FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR UPDATE", member.tenantId(), order);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
        if (member.role().name().equals("TECHNICIAN") && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.work_order_transition_facts WHERE tenant_id = ? AND work_order_id = ? AND technician_id = ?)", Boolean.class, member.tenantId(), order, member.userId())))
            throw new AccessDeniedException("OS não atribuída a este técnico.");
        return rows.getFirst();
    }
    private List<Quote.Item> items(UUID tenant, UUID quote) {
        return jdbc.query("SELECT * FROM public.quote_items WHERE tenant_id = ? AND quote_id = ? ORDER BY position", (rs, row) ->
                new Quote.Item(rs.getString("type"), rs.getObject("part_id", UUID.class), rs.getString("description"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price")), tenant, quote);
    }
    private History history(UUID tenant, UUID order, int workOrderVersion) {
        var quotes = jdbc.query("""
                SELECT q.*, CASE
                    WHEN q.status = 'DRAFT' OR NOT EXISTS(SELECT 1 FROM public.quote_access_tokens a WHERE a.tenant_id = q.tenant_id AND a.quote_id = q.id) THEN 'NONE'
                    WHEN EXISTS(SELECT 1 FROM public.quote_access_tokens a WHERE a.tenant_id = q.tenant_id AND a.quote_id = q.id AND a.revoked_at IS NULL
                        AND CASE WHEN q.status = 'SENT' THEN a.expires_at ELSE a.read_until END > clock_timestamp()) THEN 'ACTIVE'
                    WHEN EXISTS(SELECT 1 FROM public.quote_access_tokens a WHERE a.tenant_id = q.tenant_id AND a.quote_id = q.id AND a.revoked_at IS NULL) THEN 'EXPIRED'
                    ELSE 'REVOKED' END AS link_status
                FROM public.quotes q WHERE q.tenant_id = ? AND q.work_order_id = ? ORDER BY q.version DESC
                """, (rs, row) ->
                new Quote(rs.getObject("id", UUID.class), order, rs.getInt("version"), rs.getString("status"), items(tenant, rs.getObject("id", UUID.class)),
                        rs.getString("discount_type"), rs.getBigDecimal("discount_value"), rs.getBigDecimal("labor_subtotal"), rs.getBigDecimal("parts_subtotal"),
                        rs.getBigDecimal("discount_amount"), rs.getBigDecimal("total"), rs.getTimestamp("valid_until").toInstant(), rs.getString("warranty_terms"),
                        rs.getString("logo_data_url"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(), rs.getString("link_status")), tenant, order);
        return new History(quotes, workOrderVersion);
    }
    @Override public History list(UUID order) {
        return tenants.execute(tenant(), member -> {
            var current = authorize(member, order, false);
            return history(member.tenantId(), order, ((Number) current.get("version")).intValue());
        });
    }
    private record Defaults(Instant until, String terms, String logo) { }
    private Defaults defaults(UUID tenant, Instant at) {
        return jdbc.queryForObject("""
                SELECT coalesce(s.quote_validity_days, 7) AS days, coalesce(s.warranty_terms, '') AS terms, s.logo_key
                FROM public.tenants t LEFT JOIN public.tenant_settings s ON s.tenant_id = t.id WHERE t.id = ?
                """, (rs, row) -> new Defaults(at.plus(rs.getInt("days"), ChronoUnit.DAYS), rs.getString("terms"), files.logoDataUrl(tenant, rs.getString("logo_key"))), tenant);
    }
    @Override public History create(UUID order, Draft draft) { return save(order, null, draft); }
    @Override public History update(UUID order, UUID quote, Draft draft) { return save(order, quote, draft); }
    private History save(UUID order, UUID quote, Draft draft) {
        return tenants.execute(tenant(), member -> {
            var current = authorize(member, order, true);
            if (((Number) current.get("version")).intValue() != draft.workOrderVersion()) throw new WorkOrderVersionConflictException();
            if (!"AGUARDANDO_ORCAMENTO".equals(current.get("status")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "O orçamento só pode ser montado em AGUARDANDO_ORCAMENTO.");
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.work_order_diagnoses WHERE tenant_id = ? AND work_order_id = ?)", Boolean.class, member.tenantId(), order)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Registre o diagnóstico antes de montar o orçamento.");
            QuoteCalculation calculation;
            try { calculation = QuoteCalculation.calculate(draft.items(), draft.discountType(), draft.discountValue()); }
            catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
            UUID id = quote == null ? UUID.randomUUID() : quote;
            int version;
            if (quote == null) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.quotes WHERE tenant_id = ? AND work_order_id = ? AND status = 'DRAFT')", Boolean.class, member.tenantId(), order)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um rascunho. Atualize a versão vigente.");
                version = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM public.quotes WHERE tenant_id = ? AND work_order_id = ?", Integer.class, member.tenantId(), order);
            } else {
                var rows = jdbc.queryForList("SELECT version, status FROM public.quotes WHERE tenant_id = ? AND work_order_id = ? AND id = ? FOR UPDATE", member.tenantId(), order, quote);
                if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Orçamento não encontrado nesta OS.");
                if (!"DRAFT".equals(rows.getFirst().get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Orçamento enviado é imutável. Crie uma nova versão.");
                version = ((Number) rows.getFirst().get("version")).intValue();
            }
            for (var item : draft.items()) {
                if (item.partId() != null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.parts WHERE tenant_id = ? AND id = ? AND active)", Boolean.class, member.tenantId(), item.partId())))
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Peça não encontrada ou inativa.");
            }
            Instant now = jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
            var defaults = defaults(member.tenantId(), now);
            if (quote == null) {
                jdbc.update("""
                        INSERT INTO public.quotes(id, tenant_id, work_order_id, version, discount_type, discount_value,
                            labor_subtotal, parts_subtotal, discount_amount, total, valid_until, warranty_terms, logo_data_url)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, member.tenantId(), order, version, draft.discountType(), draft.discountValue(), calculation.laborSubtotal(),
                        calculation.partsSubtotal(), calculation.discountAmount(), calculation.total(), Timestamp.from(defaults.until()), defaults.terms(), defaults.logo());
            } else {
                jdbc.update("""
                        UPDATE public.quotes SET discount_type = ?, discount_value = ?, labor_subtotal = ?, parts_subtotal = ?,
                            discount_amount = ?, total = ?, valid_until = ?, warranty_terms = ?, logo_data_url = ? WHERE tenant_id = ? AND id = ?
                        """, draft.discountType(), draft.discountValue(), calculation.laborSubtotal(), calculation.partsSubtotal(), calculation.discountAmount(),
                        calculation.total(), Timestamp.from(defaults.until()), defaults.terms(), defaults.logo(), member.tenantId(), id);
                jdbc.update("DELETE FROM public.quote_items WHERE tenant_id = ? AND quote_id = ?", member.tenantId(), id);
            }
            int position = 0;
            for (var item : draft.items()) jdbc.update("""
                    INSERT INTO public.quote_items(id, tenant_id, quote_id, position, type, part_id, description, quantity, unit_price)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), member.tenantId(), id, position++, item.type(), item.partId(), item.description().strip(), item.quantity(), item.unitPrice());
            jdbc.update("""
                    INSERT INTO public.work_order_transition_facts(tenant_id, work_order_id, quote_id, quote_version, quote_status, quote_item_count, quote_total, quote_valid_until)
                    VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?) ON CONFLICT (tenant_id, work_order_id) DO UPDATE SET
                        quote_id = EXCLUDED.quote_id, quote_version = EXCLUDED.quote_version, quote_status = 'DRAFT',
                        quote_item_count = EXCLUDED.quote_item_count, quote_total = EXCLUDED.quote_total,
                        quote_valid_until = EXCLUDED.quote_valid_until, quote_token_hash = NULL
                    """, member.tenantId(), order, id, version, position, calculation.total(), Timestamp.from(defaults.until()));
            jdbc.update("UPDATE public.work_orders SET version = version + 1 WHERE tenant_id = ? AND id = ?", member.tenantId(), order);
            audit.record(member.userId(), "QUOTE_DRAFT_SAVED", "WORK_ORDER", order,
                    mapper.writeValueAsString(Map.of("quoteId", id, "quoteVersion", version, "total", calculation.total())));
            return history(member.tenantId(), order, draft.workOrderVersion() + 1);
        });
    }
    @Override public String sent(UUID tenant, UUID order, UUID quote, Instant at) {
        // Legacy transition facts may predate the quotes module; do not invent their commercial details.
        if (quote == null || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.quotes WHERE tenant_id = ? AND work_order_id = ? AND id = ?)", Boolean.class, tenant, order, quote))) return null;
        var secret = QuoteTokenSecrets.generate();
        var defaults = defaults(tenant, at);
        int changed = jdbc.update("""
                UPDATE public.quotes SET status = 'SENT', sent_at = ?, valid_until = ?, warranty_terms = ?, logo_data_url = ?
                WHERE tenant_id = ? AND work_order_id = ? AND id = ? AND status = 'DRAFT'
                """, Timestamp.from(at), Timestamp.from(defaults.until()), defaults.terms(), defaults.logo(), tenant, order, quote);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "O orçamento já foi enviado.");
        jdbc.update("INSERT INTO public.quote_access_tokens(tenant_id, quote_id, token_hash, expires_at, read_until) VALUES (?, ?, ?, ?, ?)",
                tenant, quote, secret.hash(), Timestamp.from(defaults.until()), Timestamp.from(defaults.until().plus(90, ChronoUnit.DAYS)));
        jdbc.update("UPDATE public.work_order_transition_facts SET quote_status = 'SENT', quote_valid_until = ?, quote_token_hash = ? WHERE tenant_id = ? AND work_order_id = ?", Timestamp.from(defaults.until()), secret.hash(), tenant, order);
        return emails.seal(tenant, order, quote, secret.token(), defaults.until());
    }
    @Override public void revoke(UUID tenant, UUID order) {
        jdbc.update("UPDATE public.quote_access_tokens a SET revoked_at = clock_timestamp() FROM public.quotes q WHERE q.tenant_id = a.tenant_id AND q.id = a.quote_id AND q.tenant_id = ? AND q.work_order_id = ? AND a.revoked_at IS NULL", tenant, order);
    }
    @Override public ManualApproval approveManually(UUID tenant, UUID order, UUID quote, UUID actor, String channel, String reason, String ip, String userAgent, Instant at) {
        String json = jdbc.queryForObject("SELECT public.record_manual_quote_approval(?, ?, ?, ?, ?, ?, ?, ?, ?)::text", String.class,
                tenant, order, quote, actor, channel, reason, ip, userAgent, Timestamp.from(at));
        var result = mapper.readTree(json);
        int status = result.get("status").asInt();
        if (status == 403) throw new AccessDeniedException("Aprovação manual não autorizada.");
        if (status == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Orçamento vigente não encontrado nesta OS.");
        if (status == 400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe canal e justificativa válidos para a aprovação manual.");
        if (status != 200) throw new ResponseStatusException(HttpStatus.CONFLICT, "A proposta não está disponível para aprovação manual. Confira o estado e a validade.");
        return mapper.readValue(result.get("approval").toString(), ManualApproval.class);
    }
    @Override public void decided(UUID tenant, UUID quote, String status, Instant at) {
        if (!Set.of("APPROVED", "REJECTED").contains(status)) throw new IllegalArgumentException("Decisão inválida");
        jdbc.update("UPDATE public.quotes SET status = ? WHERE tenant_id = ? AND id = ? AND status = 'SENT'", status, tenant, quote);
        jdbc.update("UPDATE public.quote_access_tokens SET read_until = ? WHERE tenant_id = ? AND quote_id = ? AND revoked_at IS NULL", Timestamp.from(at.plus(90, ChronoUnit.DAYS)), tenant, quote);
    }
}
