package br.com.fixflow.modules.workorders.infrastructure;

import br.com.fixflow.modules.workorders.application.WorkOrders;
import br.com.fixflow.modules.workorders.domain.*;
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
    private static final String SELECT = "SELECT w.*, c.name AS customer_name, concat_ws(' ', nullif(e.brand, ''), e.model) AS equipment_label, e.serial_number, f.technician_id, t.full_name AS technician_name, d.id AS diagnosis_id, d.revision AS diagnosis_revision, d.findings, d.probable_cause, d.proposed_solution, d.recorded_by, d.created_at AS diagnosis_created_at, a.full_name AS diagnosis_author FROM public.work_orders w JOIN public.customers c ON c.tenant_id = w.tenant_id AND c.id = w.customer_id LEFT JOIN public.equipment e ON e.tenant_id = w.tenant_id AND e.id = w.equipment_id LEFT JOIN public.work_order_transition_facts f ON f.tenant_id = w.tenant_id AND f.work_order_id = w.id LEFT JOIN public.work_order_team(w.tenant_id) t ON t.user_id = f.technician_id LEFT JOIN LATERAL (SELECT * FROM public.work_order_diagnoses d WHERE d.tenant_id = w.tenant_id AND d.work_order_id = w.id ORDER BY d.revision DESC LIMIT 1) d ON true LEFT JOIN public.work_order_team(w.tenant_id) a ON a.user_id = d.recorded_by";
    private WorkOrder mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkOrder(rs.getObject("id", UUID.class), rs.getLong("number"), rs.getObject("customer_id", UUID.class),
                        rs.getObject("equipment_id", UUID.class), rs.getString("status"), rs.getString("reported_issue"),
                        mapper.readTree(rs.getString("received_accessories")).valueStream().map(n -> n.asText()).toList(),
                        rs.getString("physical_condition"), rs.getTimestamp("created_at").toInstant(), rs.getInt("version"), rs.getString("customer_name"), rs.getString("equipment_label"), rs.getString("serial_number"), rs.getObject("technician_id", UUID.class), rs.getString("technician_name"), rs.getObject("diagnosis_id") == null ? null : new Diagnosis(rs.getObject("diagnosis_id", UUID.class), rs.getInt("diagnosis_revision"), rs.getString("findings"), rs.getString("probable_cause"), rs.getString("proposed_solution"), rs.getObject("recorded_by", UUID.class), rs.getString("diagnosis_author"), rs.getTimestamp("diagnosis_created_at").toInstant()));
    }
    @Override public Page search(UUID technicianId, String status, boolean openOnly, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginação inválida.");
        if (status != null && !status.isBlank()) {
            try { WorkOrderStatus.valueOf(status); }
            catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado inválido."); }
        }
        return tenants.execute(tenant(), m -> {
            UUID filter = technicianId;
            var permissions = RolePermissions.forRole(m.role().name());
            if (!permissions.contains("workorder:read")) {
                if (!permissions.contains("workorder:read_assigned")) throw new AccessDeniedException("Consulta de OS não autorizada.");
                if (filter != null && !filter.equals(m.userId())) throw new AccessDeniedException("Consulta restrita às OS atribuídas a você.");
                filter = m.userId();
            }
            var args = new java.util.ArrayList<Object>(); args.add(m.tenantId());
            String where = " WHERE w.tenant_id = ?";
            if (filter != null) { where += " AND f.technician_id = ?"; args.add(filter); }
            if (status != null && !status.isBlank()) { where += " AND w.status = ?"; args.add(status); }
            if (openOnly) where += " AND w.status NOT IN ('ENTREGUE', 'CANCELADA')";
            long total = jdbc.queryForObject("SELECT count(*) FROM public.work_orders w LEFT JOIN public.work_order_transition_facts f ON f.tenant_id = w.tenant_id AND f.work_order_id = w.id" + where, Long.class, args.toArray());
            args.add(size); args.add(page * size);
            return new Page(jdbc.query(SELECT + where + " ORDER BY w.created_at DESC, w.id DESC LIMIT ? OFFSET ?", (rs, row) -> mapOrder(rs), args.toArray()), total, page, size);
        });
    }
    private WorkOrder read(UUID tenant, UUID id) {
        var rows = jdbc.query(SELECT + " WHERE w.tenant_id = ? AND w.id = ?", (rs, row) -> mapOrder(rs), tenant, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
        return rows.getFirst();
    }
    @Override public WorkOrder get(UUID id) {
        return tenants.execute(tenant(), m -> {
            if (!RolePermissions.forRole(m.role().name()).contains("workorder:read") &&
                    !(m.role().name().equals("TECHNICIAN") && m.userId().equals(facts(m.tenantId(), id).technicianId())))
                throw new AccessDeniedException("Consulta de OS não autorizada.");
            return read(m.tenantId(), id);
        });
    }
    @Override public List<Technician> technicians() {
        return tenants.execute(tenant(), m -> {
            if (!RolePermissions.forRole(m.role().name()).contains("workorder:assign")) throw new AccessDeniedException("Atribuição não autorizada.");
            return jdbc.query("SELECT user_id, full_name FROM public.work_order_team(?) WHERE active AND role IN ('OWNER', 'TECHNICIAN') ORDER BY lower(full_name), user_id",
                    (rs, row) -> new Technician(rs.getObject("user_id", UUID.class), rs.getString("full_name")), m.tenantId());
        });
    }
    @Override public WorkOrder assignTechnician(UUID id, UUID technicianId, int version) {
        return tenants.execute(tenant(), m -> {
            if (!RolePermissions.forRole(m.role().name()).contains("workorder:assign")) throw new AccessDeniedException("Atribuição não autorizada.");
            var rows = jdbc.queryForList("SELECT id FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR UPDATE", UUID.class, m.tenantId(), id);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
            var order = read(m.tenantId(), id);
            if (order.version() != version) throw new WorkOrderVersionConflictException();
            if (order.status().equals("ENTREGUE") || order.status().equals("CANCELADA")) throw new InvalidStateTransitionException("Não é possível atribuir técnico a uma OS encerrada.");
            var candidates = jdbc.queryForList("SELECT user_id FROM public.work_order_team(?) WHERE user_id = ? AND active AND role IN ('OWNER', 'TECHNICIAN')", UUID.class, m.tenantId(), technicianId);
            if (candidates.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Técnico ativo não encontrado nesta empresa.");
            if (technicianId.equals(order.technicianId())) return order;
            jdbc.update("""
                    INSERT INTO public.work_order_transition_facts(tenant_id, work_order_id, technician_id) VALUES (?, ?, ?)
                    ON CONFLICT (tenant_id, work_order_id) DO UPDATE SET technician_id = EXCLUDED.technician_id
                    """, m.tenantId(), id, technicianId);
            if (jdbc.update("UPDATE public.work_orders SET version = version + 1 WHERE tenant_id = ? AND id = ? AND version = ?", m.tenantId(), id, version) != 1)
                throw new WorkOrderVersionConflictException();
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("workOrderId", id); payload.put("previousTechnicianId", order.technicianId());
            payload.put("technicianId", technicianId); payload.put("version", version + 1);
            audit.record(m.userId(), "WORK_ORDER_TECHNICIAN_ASSIGNED", "WORK_ORDER", id, mapper.writeValueAsString(payload));
            outbox.record("WorkOrder", id, "WorkOrderTechnicianAssigned", mapper.writeValueAsString(payload));
            return read(m.tenantId(), id);
        });
    }
    private WorkOrderFacts facts(UUID tenant, UUID id) {
        var rows = jdbc.query("""
                SELECT f.*, CASE WHEN EXISTS (SELECT 1 FROM public.work_order_diagnoses d WHERE d.tenant_id = f.tenant_id AND d.work_order_id = f.work_order_id) THEN 'registered' END AS registered_diagnosis, m.user_id AS active_technician FROM public.work_order_transition_facts f
                LEFT JOIN public.work_order_team(f.tenant_id) m ON m.user_id = f.technician_id
                    AND m.active AND m.role IN ('TECHNICIAN', 'OWNER')
                WHERE f.tenant_id = ? AND f.work_order_id = ? FOR UPDATE OF f
                """, (rs, row) -> new WorkOrderFacts(rs.getObject("active_technician", UUID.class), rs.getString("registered_diagnosis"),
                rs.getObject("quote_id", UUID.class), rs.getString("quote_status"), rs.getInt("quote_item_count"),
                rs.getBigDecimal("quote_total"), rs.getTimestamp("quote_valid_until") == null ? null : rs.getTimestamp("quote_valid_until").toInstant(),
                rs.getInt("service_count"), rs.getBoolean("parts_consumed"), rs.getBoolean("parts_written_off"),
                rs.getBoolean("payment_settled"), rs.getBoolean("diagnostic_fee_registered"), rs.getString("missing_part"),
                rs.getBoolean("maintenance_started"), rs.getInt("warranty_days")), tenant, id);
        return rows.isEmpty() ? WorkOrderFacts.empty() : rows.getFirst();
    }
    @Override public WorkOrder transition(UUID id, String toStatus, int version, String reason, boolean paymentOverride) {
        return tenants.execute(tenant(), m -> {
            var ids = jdbc.queryForList("SELECT id FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR UPDATE", UUID.class, m.tenantId(), id);
            if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
            WorkOrder order = read(m.tenantId(), id);
            var facts = facts(m.tenantId(), id);
            // A technician must never inspect or mutate another technician's OS.
            if (m.role().name().equals("TECHNICIAN") && !m.userId().equals(facts.technicianId())) throw new AccessDeniedException("OS não atribuída a este técnico.");
            if (order.version() != version) throw new WorkOrderVersionConflictException();
            WorkOrderStatus target;
            try { target = WorkOrderStatus.valueOf(toStatus); }
            catch (IllegalArgumentException exception) { throw new InvalidStateTransitionException("Estado de destino inválido."); }
            var from = WorkOrderStatus.valueOf(order.status());
            var transition = new WorkOrderStateMachine().validate(from, target, m.role().name(), m.userId(), facts,
                    reason, paymentOverride, java.time.Instant.now());
            int changed = jdbc.update("""
                    UPDATE public.work_orders SET status = ?, version = version + 1,
                        ready_at = CASE WHEN ? = 'PRONTA' THEN CURRENT_TIMESTAMP ELSE ready_at END,
                        delivered_at = CASE WHEN ? = 'ENTREGUE' THEN CURRENT_TIMESTAMP ELSE delivered_at END,
                        warranty_days = CASE WHEN ? = 'ENTREGUE' AND ? = 'PRONTA' THEN ? ELSE warranty_days END,
                        warranty_until = CASE WHEN ? = 'ENTREGUE' AND ? = 'PRONTA'
                            THEN (CURRENT_TIMESTAMP AT TIME ZONE 'America/Sao_Paulo')::date + ? ELSE warranty_until END
                    WHERE tenant_id = ? AND id = ? AND version = ?
                    """, target.name(), target.name(), target.name(), target.name(), from.name(), facts.warrantyDays(),
                    target.name(), from.name(), facts.warrantyDays(), m.tenantId(), id, version);
            if (changed != 1) throw new WorkOrderVersionConflictException();
            String publicToken = null;
            if (target == WorkOrderStatus.AGUARDANDO_APROVACAO) {
                byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
                publicToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                String hash;
                try { hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(publicToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
                catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
                jdbc.update("UPDATE public.work_order_transition_facts SET quote_status = 'SENT', quote_token_hash = ? WHERE tenant_id = ? AND work_order_id = ?", hash, m.tenantId(), id);
            }
            if (target == WorkOrderStatus.APROVADA || target == WorkOrderStatus.ORCAMENTO_REJEITADO) {
                jdbc.update("UPDATE public.work_order_transition_facts SET quote_status = ?, quote_token_hash = NULL WHERE tenant_id = ? AND work_order_id = ?",
                        target == WorkOrderStatus.APROVADA ? "APPROVED" : "REJECTED", m.tenantId(), id);
            }
            if (target == WorkOrderStatus.AGUARDANDO_ORCAMENTO && from != WorkOrderStatus.EM_DIAGNOSTICO) {
                jdbc.update("""
                        UPDATE public.work_order_transition_facts SET quote_status = 'DRAFT', quote_id = NULL,
                            quote_version = quote_version + 1, quote_token_hash = NULL,
                            quote_item_count = 0, quote_total = 0, quote_valid_until = NULL
                        WHERE tenant_id = ? AND work_order_id = ?
                        """, m.tenantId(), id);
            }
            if (target == WorkOrderStatus.EM_MANUTENCAO) jdbc.update("UPDATE public.work_order_transition_facts SET maintenance_started = true WHERE tenant_id = ? AND work_order_id = ?", m.tenantId(), id);
            if (target == WorkOrderStatus.CANCELADA || target == WorkOrderStatus.ENTREGUE)
                jdbc.update("UPDATE public.work_order_transition_facts SET quote_token_hash = NULL WHERE tenant_id = ? AND work_order_id = ?", m.tenantId(), id);
            jdbc.update("""
                    INSERT INTO public.work_order_status_history(id, tenant_id, work_order_id, from_status, to_status, changed_by, source, reason)
                    VALUES (?, ?, ?, ?, ?, ?, 'USER', ?)
                    """, UUID.randomUUID(), m.tenantId(), id, from.name(), target.name(), m.userId(), reason);
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("workOrderId", id); payload.put("fromStatus", from.name()); payload.put("toStatus", target.name());
            payload.put("version", version + 1); payload.put("reason", reason); payload.put("paymentOverride", paymentOverride);
            audit.record(m.userId(), "WORK_ORDER_STATUS_CHANGED", "WORK_ORDER", id, mapper.writeValueAsString(payload));
            // Token is only delivered to the notification consumer, never to audit/logs.
            if (publicToken != null) payload.put("publicToken", publicToken);
            if (transition.eventType() != null) outbox.record("WorkOrder", id, transition.eventType(), mapper.writeValueAsString(payload));
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
