package br.com.fixflow.modules.workorders.infrastructure;
import br.com.fixflow.modules.workorders.application.WorkOrderItems;
import br.com.fixflow.modules.workorders.domain.*;
import br.com.fixflow.modules.inventory.application.StockOperations;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.tenant.*;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.application.outbox.OutboxService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class JdbcWorkOrderItems implements WorkOrderItems {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final StockOperations stock;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    public JdbcWorkOrderItems(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity,
                              StockOperations stock, AuditService audit, OutboxService outbox, ObjectMapper mapper) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.stock = stock;
        this.audit = audit; this.outbox = outbox; this.mapper = mapper;
    }
    private UUID tenant() {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new org.springframework.security.authentication.InsufficientAuthenticationException("Selecione uma empresa.");
        return tenant;
    }
    private static final RowMapper<WorkOrderItem> ROW = (rs, n) -> new WorkOrderItem(rs.getObject("id", UUID.class), rs.getString("type"),
            rs.getObject("part_id", UUID.class), rs.getString("description"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"),
            rs.getBigDecimal("unit_cost"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("removed_at") == null ? null : rs.getTimestamp("removed_at").toInstant());
    private Map<String, Object> authorize(AuthorizedMembership m, UUID order, boolean writing) {
        var p = RolePermissions.forRole(m.role().name());
        if (writing ? !p.contains("workorder:execute") : !p.contains("workorder:read") && !p.contains("workorder:read_assigned"))
            throw new AccessDeniedException("Acesso aos itens da OS não autorizado.");
        var rows = jdbc.queryForList("SELECT status, version FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR UPDATE", m.tenantId(), order);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
        if (m.role().name().equals("TECHNICIAN") && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.work_order_transition_facts WHERE tenant_id = ? AND work_order_id = ? AND technician_id = ?)", Boolean.class, m.tenantId(), order, m.userId())))
            throw new AccessDeniedException("OS não atribuída a este técnico.");
        return rows.getFirst();
    }
    private void writable(Map<String, Object> order, int version) {
        if (((Number) order.get("version")).intValue() != version) throw new WorkOrderVersionConflictException();
        if (!"EM_MANUTENCAO".equals(order.get("status"))) throw new InvalidStateTransitionException("Itens executados só podem ser alterados durante EM_MANUTENCAO.");
    }
    private Saved load(UUID tenant, UUID order, int version) {
        return new Saved(jdbc.query("SELECT * FROM public.work_order_items WHERE tenant_id = ? AND work_order_id = ? AND removed_at IS NULL ORDER BY created_at, id", ROW, tenant, order), version);
    }
    @Override public Saved list(UUID order) {
        return tenants.execute(tenant(), m -> {
            var current = authorize(m, order, false);
            return load(m.tenantId(), order, ((Number) current.get("version")).intValue());
        });
    }
    private String fingerprint(Add item) {
        String canonical = mapper.writeValueAsString(List.of(item.type(), item.partId() == null ? "" : item.partId().toString(),
                item.description(), item.quantity().stripTrailingZeros().toPlainString(), item.unitPrice().stripTrailingZeros().toPlainString()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public Saved add(UUID order, int version, Add item) {
        return tenants.execute(tenant(), m -> {
            var current = authorize(m, order, true);
            String hash = fingerprint(item);
            var existing = jdbc.queryForList("SELECT work_order_id, request_hash FROM public.work_order_items WHERE tenant_id = ? AND id = ?", m.tenantId(), item.id());
            if (!existing.isEmpty()) {
                if (!order.equals(existing.getFirst().get("work_order_id")) || !hash.equals(existing.getFirst().get("request_hash")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador de item já utilizado com outros dados.");
                return load(m.tenantId(), order, ((Number) current.get("version")).intValue());
            }
            writable(current, version);
            if (!Set.of("SERVICE", "PART").contains(item.type()) || item.type().equals("PART") != (item.partId() != null))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma peça somente para itens do tipo PART.");
            // A cross-tenant part must be rejected before the composite FK is evaluated.
            if (item.partId() != null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.parts WHERE tenant_id = ? AND id = ? AND active)", Boolean.class, m.tenantId(), item.partId())))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Peça não encontrada ou inativa.");
            int inserted = jdbc.update("""
                    INSERT INTO public.work_order_items(id, tenant_id, work_order_id, type, part_id, description, quantity, unit_price, unit_cost, request_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?) ON CONFLICT (id) DO NOTHING
                    """, item.id(), m.tenantId(), order, item.type(), item.partId(), item.description(), item.quantity(), item.unitPrice(), hash);
            if (inserted != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador de item já utilizado.");
            if (item.partId() != null) {
                var part = stock.consume(m.tenantId(), m.userId(), item.partId(), order, item.id(), item.quantity());
                jdbc.update("UPDATE public.work_order_items SET unit_cost = ? WHERE tenant_id = ? AND id = ?", part.cost(), m.tenantId(), item.id());
            }
            changed(m, order, item.id(), "ADDED");
            return load(m.tenantId(), order, version + 1);
        });
    }
    @Override public Saved remove(UUID order, UUID item, int version) {
        return tenants.execute(tenant(), m -> {
            var current = authorize(m, order, true);
            var rows = jdbc.query("SELECT * FROM public.work_order_items WHERE tenant_id = ? AND work_order_id = ? AND id = ?", ROW, m.tenantId(), order, item);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item não encontrado nesta OS.");
            var existing = rows.getFirst();
            if (existing.removedAt() != null) return load(m.tenantId(), order, ((Number) current.get("version")).intValue());
            writable(current, version);
            if (existing.partId() != null) stock.restore(m.tenantId(), m.userId(), existing.partId(), order, item, existing.quantity(), existing.unitCost());
            jdbc.update("UPDATE public.work_order_items SET removed_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?", m.tenantId(), item);
            changed(m, order, item, "REMOVED");
            return load(m.tenantId(), order, version + 1);
        });
    }
    private void changed(AuthorizedMembership m, UUID order, UUID item, String action) {
        jdbc.update("UPDATE public.work_orders SET version = version + 1 WHERE tenant_id = ? AND id = ?", m.tenantId(), order);
        jdbc.update("""
                INSERT INTO public.work_order_transition_facts(tenant_id, work_order_id, service_count, parts_consumed, parts_written_off)
                SELECT ?, ?, count(*) FILTER (WHERE type = 'SERVICE'), count(*) FILTER (WHERE type = 'PART') > 0, count(*) FILTER (WHERE type = 'PART') > 0
                FROM public.work_order_items WHERE tenant_id = ? AND work_order_id = ? AND removed_at IS NULL
                ON CONFLICT (tenant_id, work_order_id) DO UPDATE SET service_count = EXCLUDED.service_count,
                    parts_consumed = EXCLUDED.parts_consumed, parts_written_off = EXCLUDED.parts_written_off
                """, m.tenantId(), order, m.tenantId(), order);
        String payload = mapper.writeValueAsString(Map.of("workOrderId", order, "itemId", item));
        audit.record(m.userId(), "WORK_ORDER_ITEM_" + action, "WORK_ORDER", order, payload);
        outbox.record("WorkOrder", order, action.equals("ADDED") ? "WorkOrderItemAdded" : "WorkOrderItemRemoved", payload);
    }
}
