package br.com.fixflow.modules.billing.infrastructure;

import br.com.fixflow.modules.billing.application.PaymentGateway;
import br.com.fixflow.modules.billing.application.PixCharges;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class JdbcPixCharges implements PixCharges {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final PaymentGateway gateway;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    public JdbcPixCharges(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity,
            PaymentGateway gateway, AuditService audit, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.gateway = gateway; this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
    }
    private UUID tenant() {
        UUID id = identity.require().tenantId();
        if (id == null) throw new org.springframework.security.authentication.InsufficientAuthenticationException("Selecione uma empresa.");
        return id;
    }
    private void permission(String role) {
        if (!RolePermissions.forRole(role).contains("payment:register")) throw new AccessDeniedException("Cobranças não autorizadas.");
    }
    private void lockOrder(UUID tenant, UUID order) {
        if (jdbc.queryForList("SELECT id FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, order).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
    }
    private static final RowMapper<Charge> ROW = (rs, n) -> new Charge(rs.getObject("id", UUID.class), rs.getObject("work_order_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getObject("due_date", LocalDate.class), rs.getString("status"), rs.getString("gateway_ref"),
            rs.getString("pix_payload"), rs.getString("pix_image"), rs.getString("pix_expiration"));
    @Override public List<Charge> list(UUID order) {
        return tenants.execute(tenant(), m -> {
            permission(m.role().name()); lockOrder(m.tenantId(), order);
            return jdbc.query("SELECT * FROM public.pix_charges WHERE tenant_id = ? AND work_order_id = ? ORDER BY created_at DESC, id", ROW, m.tenantId(), order);
        });
    }
    private record SavedRequest(String key, String customer, BigDecimal amount, LocalDate dueDate) { }
    @Override public Charge refresh(UUID order, UUID id) {
        SavedRequest request = tenants.execute(tenant(), m -> {
            permission(m.role().name());
            var rows = jdbc.query("SELECT * FROM public.pix_charges WHERE tenant_id = ? AND work_order_id = ? AND id = ?",
                    (rs, n) -> new SavedRequest(rs.getString("idempotency_key"), rs.getString("asaas_customer"),
                            rs.getBigDecimal("amount"), rs.getObject("due_date", LocalDate.class)), m.tenantId(), order, id);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada.");
            return rows.getFirst();
        });
        return create(order, request.key(), request.customer(), request.amount(), request.dueDate());
    }
    private record Attempt(Charge charge, boolean fresh, String document) { }
    @Override public Charge create(UUID order, String key, String customer, BigDecimal amount, LocalDate dueDate) {
        UUID tenant = tenant();
        Attempt attempt = tenants.execute(tenant, m -> {
            permission(m.role().name()); gateway.requireAvailable();
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tenant + ":pix:" + key);
            lockOrder(tenant, order);
            var previous = jdbc.query("SELECT * FROM public.pix_charges WHERE tenant_id = ? AND idempotency_key = ?", ROW, tenant, key);
            if (!previous.isEmpty()) {
                Charge c = previous.getFirst();
                String payer = jdbc.queryForObject("SELECT asaas_customer FROM public.pix_charges WHERE id = ? AND tenant_id = ?", String.class, c.id(), tenant);
                if (!c.workOrderId().equals(order) || c.amount().compareTo(amount) != 0 || !c.dueDate().equals(dueDate) || !customer.equals(payer))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Chave de idempotência já utilizada com outros dados.");
                return new Attempt(c, false, null);
            }
            if (dueDate.isBefore(LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"))))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vencimento não pode estar no passado.");
            if (jdbc.queryForList("SELECT id FROM public.quotes WHERE tenant_id = ? AND work_order_id = ? AND status = 'APPROVED'", tenant, order).isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A OS precisa de um orçamento aprovado.");
            String document = jdbc.queryForObject("SELECT c.document FROM public.customers c JOIN public.work_orders w ON w.tenant_id = c.tenant_id AND w.customer_id = c.id WHERE w.tenant_id = ? AND w.id = ? AND c.deleted_at IS NULL", String.class, tenant, order);
            document = document == null ? "" : document.replaceAll("[^0-9]", "");
            if (!document.matches("[0-9]{11}|[0-9]{14}")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cadastre CPF/CNPJ do cliente antes de gerar a cobrança PIX.");
            Charge charge = jdbc.query("""
                    INSERT INTO public.pix_charges(id, tenant_id, work_order_id, amount, due_date, asaas_customer, idempotency_key, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                    """, ROW, UUID.randomUUID(), tenant, order, amount, dueDate, customer, key, m.userId()).getFirst();
            audit.record(m.userId(), "PIX_CHARGE_REQUESTED", "WORK_ORDER", order, "{\"chargeId\":\"" + charge.id() + "\"}");
            return new Attempt(charge, true, document);
        });
        Charge charge = attempt.charge();
        if (charge.payload() != null || charge.status().equals("RECEIVED")) return charge;
        // An inconclusive attempt is recovered by lookup only. Never resend a payment POST blindly.
        PaymentGateway.RemotePayment remote = attempt.fresh()
                ? gateway.createPix(new PaymentGateway.PixRequest(charge.id(), customer, attempt.document(), amount, dueDate))
                : gateway.findPix(charge.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Cobrança ainda não localizada no Asaas. Repita com a mesma chave para consultar novamente."));
        if (!charge.id().toString().equals(remote.externalReference()) || !customer.equals(remote.customer())
                || !"PIX".equals(remote.billingType()) || amount.compareTo(remote.value()) != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dados da cobrança Asaas não correspondem à solicitação.");
        var code = gateway.qrCode(remote.id());
        return tenants.execute(tenant, m -> {
            permission(m.role().name()); lockOrder(tenant, order);
            var current = jdbc.query("SELECT * FROM public.pix_charges WHERE tenant_id = ? AND id = ? FOR UPDATE", ROW, tenant, charge.id()).getFirst();
            if (current.gatewayRef() != null && !current.gatewayRef().equals(remote.id())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cobrança já vinculada a outro pagamento.");
            return jdbc.query("""
                    UPDATE public.pix_charges SET gateway_ref = ?, pix_payload = ?, pix_image = ?, pix_expiration = ?,
                        status = CASE WHEN status = 'RECEIVED' THEN status ELSE 'PENDING' END
                    WHERE tenant_id = ? AND id = ? RETURNING *
                    """, ROW, remote.id(), code.payload(), code.encodedImage(), code.expirationDate(), tenant, charge.id()).getFirst();
        });
    }
    @Override public void webhook(String token, String eventId, String event, String paymentId) {
        gateway.authenticateWebhook(token);
        if (!eventId.matches("evt_[a-zA-Z0-9_\\-]+") || eventId.length() > 200 || event.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento Asaas inválido.");
        if (!"PAYMENT_RECEIVED".equals(event)) return;
        var remote = gateway.retrieve(paymentId);
        if (!paymentId.equals(remote.id()) || !"PIX".equals(remote.billingType()) || !"RECEIVED".equals(remote.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recebimento PIX ainda não confirmado no Asaas.");
        UUID reference;
        try { reference = UUID.fromString(remote.externalReference()); }
        catch (IllegalArgumentException ex) { return; } // Other payments in the shared Asaas account are unrelated.
        transaction.executeWithoutResult(tx -> {
            Boolean accepted = jdbc.queryForObject("SELECT public.reconcile_asaas_pix(?, ?, ?, ?, ?, ?)", Boolean.class,
                    reference, eventId, remote.id(), remote.customer(), remote.value(), remote.paymentDate() == null ? null : java.sql.Date.valueOf(remote.paymentDate()));
            if (!Boolean.TRUE.equals(accepted)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Evento Asaas divergente da cobrança registrada.");
        });
    }
}
