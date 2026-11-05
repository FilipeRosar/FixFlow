package br.com.fixflow.modules.workorders.infrastructure;

import br.com.fixflow.modules.workorders.application.WorkOrderTimeline;
import br.com.fixflow.modules.workorders.domain.WorkOrderTimelineEntry;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import java.util.Map;
import java.util.UUID;

@Service
public class JdbcWorkOrderTimeline implements WorkOrderTimeline {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final ObjectMapper mapper;
    public JdbcWorkOrderTimeline(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity, ObjectMapper mapper) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.mapper = mapper;
    }
    @Override public Page list(UUID order, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginação inválida.");
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenants.execute(tenant, m -> {
            var permissions = RolePermissions.forRole(m.role().name());
            if (!permissions.contains("workorder:read") && !permissions.contains("workorder:read_assigned"))
                throw new AccessDeniedException("Consulta de histórico não autorizada.");
            // Serialize assignment changes with the technician authorization check.
            var orders = jdbc.queryForList("SELECT id FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR SHARE", tenant, order);
            if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
            if (m.role().name().equals("TECHNICIAN") && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.work_order_transition_facts WHERE tenant_id = ? AND work_order_id = ? AND technician_id = ?)", Boolean.class, tenant, order, m.userId())))
                throw new AccessDeniedException("OS não atribuída a este técnico.");
            long total = jdbc.queryForObject("SELECT count(*) FROM public.work_order_timeline WHERE tenant_id = ? AND work_order_id = ?", Long.class, tenant, order);
            var content = jdbc.query("""
                    SELECT * FROM public.work_order_timeline WHERE tenant_id = ? AND work_order_id = ?
                    ORDER BY occurred_at DESC, sequence DESC LIMIT ? OFFSET ?
                    """, (rs, row) -> new WorkOrderTimelineEntry(rs.getObject("id", UUID.class), rs.getString("event_type"),
                    rs.getObject("actor_id", UUID.class), rs.getString("actor_name"), rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("origin"), rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
                    mapper.readValue(rs.getString("details"), new TypeReference<Map<String, Object>>() { })), tenant, order, size, page * size);
            return new Page(content, total, page, size);
        });
    }
}
