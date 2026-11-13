package br.com.fixflow.modules.billing.infrastructure;

import br.com.fixflow.modules.billing.application.Payments;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Service
public class JdbcPayments implements Payments {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    private final tools.jackson.databind.ObjectMapper mapper;
    public JdbcPayments(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity, AuditService audit, tools.jackson.databind.ObjectMapper mapper) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.audit = audit; this.mapper = mapper;
    }
    private UUID tenant() {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new org.springframework.security.authentication.InsufficientAuthenticationException("Selecione uma empresa.");
        return tenant;
    }
    private void permission(String role) {
        if (!RolePermissions.forRole(role).contains("payment:register")) throw new AccessDeniedException("Pagamentos não autorizados.");
    }
    private static final RowMapper<Payment> ROW = (rs, n) -> new Payment(rs.getObject("id", UUID.class),
            rs.getObject("work_order_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("method"),
            rs.getString("status"), rs.getTimestamp("paid_at").toInstant(), rs.getString("notes"), rs.getString("void_reason"),
            rs.getTimestamp("voided_at") == null ? null : rs.getTimestamp("voided_at").toInstant(), rs.getObject("voided_by", UUID.class));
    private void order(UUID tenant, UUID order, boolean lock) {
        if (jdbc.queryForList("SELECT id FROM public.work_orders WHERE tenant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""), tenant, order).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
    }
    @Override public List<Payment> list(UUID order) {
        return tenants.execute(tenant(), m -> {
            permission(m.role().name()); order(m.tenantId(), order, false);
            return jdbc.query("SELECT * FROM public.payments WHERE tenant_id = ? AND work_order_id = ? ORDER BY paid_at, id", ROW, m.tenantId(), order);
        });
    }
    @Override public Registration register(UUID order, String key, BigDecimal amount, String method, Instant paidAt, String notes) {
        return tenants.execute(tenant(), m -> {
            permission(m.role().name());
            if (key.startsWith("asaas:")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prefixo reservado para conciliação do gateway.");
            // Serialize retries even when a key is reused for another order.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", m.tenantId() + ":payment:" + key);
            order(m.tenantId(), order, true);
            var previous = jdbc.query("SELECT * FROM public.payments WHERE tenant_id = ? AND idempotency_key = ?", ROW, m.tenantId(), key);
            if (!previous.isEmpty()) {
                Payment p = previous.getFirst();
                if (!p.workOrderId().equals(order) || p.amount().compareTo(amount) != 0 || !p.method().equals(method)
                        || !p.paidAt().equals(paidAt) || !Objects.equals(p.notes(), notes))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Chave de idempotência já utilizada com outros dados.");
                return new Registration(p, true);
            }
            if (jdbc.queryForList("SELECT id FROM public.quotes WHERE tenant_id = ? AND work_order_id = ? AND status = 'APPROVED'", m.tenantId(), order).isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A OS precisa de um orçamento aprovado.");
            UUID id = UUID.randomUUID();
            Payment payment = jdbc.query("""
                    INSERT INTO public.payments(id, tenant_id, work_order_id, amount, method, paid_at, notes, idempotency_key, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                    """, ROW, id, m.tenantId(), order, amount, method, Timestamp.from(paidAt), notes, key, m.userId()).getFirst();
            audit.record(m.userId(), "PAYMENT_REGISTERED", "WORK_ORDER", order, "{\"paymentId\":\"" + id + "\",\"amount\":" + amount.toPlainString() + "}");
            return new Registration(payment, false);
        });
    }
    @Override public Payment voidPayment(UUID paymentId, String reason) {
        return tenants.execute(tenant(), m -> {
            if (!m.role().name().equals("OWNER")) throw new AccessDeniedException("Estorno exclusivo do Owner.");
            if (reason == null || reason.isBlank() || reason.strip().length() > 2000)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo do estorno, com até 2000 caracteres.");
            String normalizedReason = reason.strip();
            var rows = jdbc.query("SELECT * FROM public.payments WHERE tenant_id = ? AND id = ?", ROW, m.tenantId(), paymentId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pagamento não encontrado.");
            UUID orderId = rows.getFirst().workOrderId();
            // Same lock order as registration/delivery; reread after concurrent transactions finish.
            order(m.tenantId(), orderId, true);
            Payment current = jdbc.query("SELECT * FROM public.payments WHERE tenant_id = ? AND id = ? FOR UPDATE", ROW, m.tenantId(), paymentId).getFirst();
            if (current.status().equals("VOIDED")) {
                if (!Objects.equals(current.voidReason(), normalizedReason))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Pagamento já estornado com outro motivo.");
                return current;
            }
            Payment voided = jdbc.query("""
                    UPDATE public.payments SET status = 'VOIDED', void_reason = ?, voided_at = clock_timestamp(), voided_by = ?
                    WHERE tenant_id = ? AND id = ? RETURNING *
                    """, ROW, normalizedReason, m.userId(), m.tenantId(), paymentId).getFirst();
            audit.record(m.userId(), "PAYMENT_VOIDED", "WORK_ORDER", orderId,
                    mapper.writeValueAsString(Map.of("paymentId", paymentId, "amount", current.amount(), "reason", normalizedReason)));
            return voided;
        });
    }

}
