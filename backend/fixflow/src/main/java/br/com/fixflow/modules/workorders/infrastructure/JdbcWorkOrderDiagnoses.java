package br.com.fixflow.modules.workorders.infrastructure;

import br.com.fixflow.modules.workorders.application.WorkOrderDiagnoses;
import br.com.fixflow.modules.workorders.domain.*;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.application.outbox.OutboxService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JdbcWorkOrderDiagnoses implements WorkOrderDiagnoses {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    public JdbcWorkOrderDiagnoses(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity,
                                  AuditService audit, OutboxService outbox, ObjectMapper mapper) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity;
        this.audit = audit; this.outbox = outbox; this.mapper = mapper;
    }
    private UUID tenant() {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenant;
    }
    private Map<String, Object> authorizedOrder(AuthorizedMembership member, UUID id, boolean writing) {
        var permissions = RolePermissions.forRole(member.role().name());
        if (writing ? !permissions.contains("workorder:diagnose") : !permissions.contains("workorder:read") && !permissions.contains("workorder:read_assigned"))
            throw new AccessDeniedException("Acesso ao diagnóstico não autorizado.");
        // Lock the OS before checking assignment so reassignment and transitions serialize with revisions.
        var rows = jdbc.queryForList("SELECT status, version FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR UPDATE", member.tenantId(), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
        if (member.role().name().equals("TECHNICIAN")) {
            Boolean assigned = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.work_order_transition_facts WHERE tenant_id = ? AND work_order_id = ? AND technician_id = ?)", Boolean.class, member.tenantId(), id, member.userId());
            if (!Boolean.TRUE.equals(assigned)) throw new AccessDeniedException("OS não atribuída a este técnico.");
        }
        return rows.getFirst();
    }
    private List<Diagnosis> load(UUID tenant, UUID id) {
        return jdbc.query("""
                SELECT d.*, t.full_name FROM public.work_order_diagnoses d
                LEFT JOIN public.work_order_team(d.tenant_id) t ON t.user_id = d.recorded_by
                WHERE d.tenant_id = ? AND d.work_order_id = ? ORDER BY d.revision DESC
                """, (rs, row) -> new Diagnosis(rs.getObject("id", UUID.class), rs.getInt("revision"), rs.getString("findings"),
                rs.getString("probable_cause"), rs.getString("proposed_solution"), rs.getObject("recorded_by", UUID.class),
                rs.getString("full_name"), rs.getTimestamp("created_at").toInstant()), tenant, id);
    }
    @Override public List<Diagnosis> history(UUID id) {
        return tenants.execute(tenant(), member -> { authorizedOrder(member, id, false); return load(member.tenantId(), id); });
    }
    @Override public Saved record(UUID id, int version, String findings, String cause, String solution) {
        return tenants.execute(tenant(), member -> {
            var order = authorizedOrder(member, id, true);
            if (((Number) order.get("version")).intValue() != version) throw new WorkOrderVersionConflictException();
            if (!"EM_DIAGNOSTICO".equals(order.get("status"))) throw new InvalidStateTransitionException("O diagnóstico só pode ser registrado ou revisado durante EM_DIAGNOSTICO.");
            int revision = jdbc.queryForObject("SELECT coalesce(max(revision), 0) + 1 FROM public.work_order_diagnoses WHERE tenant_id = ? AND work_order_id = ?", Integer.class, member.tenantId(), id);
            UUID diagnosisId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO public.work_order_diagnoses(id, tenant_id, work_order_id, revision, findings, probable_cause, proposed_solution, recorded_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, diagnosisId, member.tenantId(), id, revision, findings, cause, solution, member.userId());
            if (jdbc.update("UPDATE public.work_orders SET version = version + 1 WHERE tenant_id = ? AND id = ? AND version = ?", member.tenantId(), id, version) != 1)
                throw new WorkOrderVersionConflictException();
            String payload = mapper.writeValueAsString(Map.of("workOrderId", id, "diagnosisId", diagnosisId, "revision", revision, "version", version + 1));
            audit.record(member.userId(), "WORK_ORDER_DIAGNOSIS_RECORDED", "WORK_ORDER", id, payload);
            outbox.record("WorkOrder", id, "DiagnosisRecorded", payload);
            return new Saved(load(member.tenantId(), id).getFirst(), version + 1);
        });
    }
}
