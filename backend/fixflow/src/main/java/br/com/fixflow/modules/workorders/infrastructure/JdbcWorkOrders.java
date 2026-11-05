package br.com.fixflow.modules.workorders.infrastructure;

import br.com.fixflow.modules.workorders.application.WorkOrders;
import br.com.fixflow.modules.workorders.domain.WorkOrder;
import br.com.fixflow.modules.tenants.application.TenantLimitsService;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.application.outbox.OutboxService;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JdbcWorkOrders implements WorkOrders {
    private final JdbcTemplate jdbc;
    private final TenantLimitsService limits;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    public JdbcWorkOrders(JdbcTemplate jdbc, TenantLimitsService limits, AuthorizedTenantOperations tenants,
                          CurrentAuthenticatedIdentity identity, AuditService audit, OutboxService outbox, ObjectMapper mapper) {
        this.jdbc = jdbc; this.limits = limits; this.tenants = tenants; this.identity = identity;
        this.audit = audit; this.outbox = outbox; this.mapper = mapper;
    }
    private UUID tenant() {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenant;
    }
    private WorkOrder read(UUID tenant, UUID id) {
        var rows = jdbc.query("SELECT w.*, c.name AS customer_name, concat_ws(' ', nullif(e.brand, ''), e.model) AS equipment_label, e.serial_number FROM public.work_orders w JOIN public.customers c ON c.tenant_id = w.tenant_id AND c.id = w.customer_id LEFT JOIN public.equipment e ON e.tenant_id = w.tenant_id AND e.id = w.equipment_id WHERE w.tenant_id = ? AND w.id = ?", (rs, row) ->
                new WorkOrder(rs.getObject("id", UUID.class), rs.getLong("number"), rs.getObject("customer_id", UUID.class),
                        rs.getObject("equipment_id", UUID.class), rs.getString("status"), rs.getString("reported_issue"),
                        mapper.readTree(rs.getString("received_accessories")).valueStream().map(n -> n.asText()).toList(),
                        rs.getString("physical_condition"), rs.getTimestamp("created_at").toInstant(), rs.getInt("version"), rs.getString("customer_name"), rs.getString("equipment_label"), rs.getString("serial_number")), tenant, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
        return rows.getFirst();
    }
    @Override public WorkOrder get(UUID id) {
        return tenants.execute(tenant(), m -> {
            if (!RolePermissions.forRole(m.role().name()).contains("workorder:read"))
                throw new AccessDeniedException("Consulta de OS não autorizada.");
            return read(m.tenantId(), id);
        });
    }
    @Override public WorkOrder open(UUID customerId, UUID equipmentId, String issue, List<String> accessories, String condition) {
        UUID tenant = tenant();
        UUID actor = identity.require().userId();
        // Quota opens the authorized transaction. Do not nest another tenant executor.
        return limits.createWorkOrder(tenant, () -> {
            var customers = jdbc.queryForList("SELECT id FROM public.customers WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL FOR UPDATE", UUID.class, tenant, customerId);
            if (customers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado.");
            var equipment = jdbc.queryForList("SELECT id FROM public.equipment WHERE tenant_id = ? AND id = ? AND customer_id = ? AND deleted_at IS NULL FOR UPDATE", UUID.class, tenant, equipmentId, customerId);
            if (equipment.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipamento não encontrado para este cliente.");
            long number = jdbc.queryForObject("""
                    INSERT INTO public.work_order_counters(tenant_id, last_number) VALUES (?, 1)
                    ON CONFLICT (tenant_id) DO UPDATE SET last_number = work_order_counters.last_number + 1
                    RETURNING last_number
                    """, Long.class, tenant);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO public.work_orders(id, tenant_id, customer_id, equipment_id, number, status,
                        reported_issue, received_accessories, physical_condition)
                    VALUES (?, ?, ?, ?, ?, 'RECEBIDA', ?, ?::jsonb, ?)
                    """, id, tenant, customerId, equipmentId, number, issue, mapper.writeValueAsString(accessories), condition);
            jdbc.update("""
                    INSERT INTO public.work_order_status_history(id, tenant_id, work_order_id, to_status, changed_by, source)
                    VALUES (?, ?, ?, 'RECEBIDA', ?, 'USER')
                    """, UUID.randomUUID(), tenant, id, actor);
            String payload = mapper.writeValueAsString(Map.of("workOrderId", id, "number", number, "status", "RECEBIDA",
                    "customerId", customerId, "equipmentId", equipmentId));
            audit.record(actor, "WORK_ORDER_CREATED", "WORK_ORDER", id, payload);
            outbox.record("WorkOrder", id, "WorkOrderCreated", payload);
            return read(tenant, id);
        });
    }
}
