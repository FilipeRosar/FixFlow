package br.com.fixflow.modules.equipment.infrastructure;

import br.com.fixflow.modules.equipment.application.EquipmentManagement;
import br.com.fixflow.modules.equipment.domain.Equipment;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.function.Function;

@Service
public class JdbcEquipmentManagement implements EquipmentManagement {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    private static final String SELECT = "SELECT e.*, c.name AS customer_name, c.deleted_at IS NOT NULL AS customer_deleted FROM public.equipment e JOIN public.customers c ON c.tenant_id = e.tenant_id AND c.id = e.customer_id ";
    private static final RowMapper<Equipment> ROW = (rs, row) -> new Equipment(rs.getObject("id", UUID.class),
            rs.getObject("customer_id", UUID.class), rs.getString("customer_name"), rs.getBoolean("customer_deleted"),
            rs.getString("category"), rs.getString("brand"), rs.getString("model"), rs.getString("serial_number"), rs.getString("description"));
    public JdbcEquipmentManagement(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity, AuditService audit) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.audit = audit;
    }
    private <T> T execute(String permission, Function<AuthorizedMembership, T> action) {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenants.execute(tenant, m -> {
            if (!RolePermissions.forRole(m.role().name()).contains(permission))
                throw new AccessDeniedException("Acesso a equipamentos não autorizado.");
            return action.apply(m);
        });
    }
    private Equipment read(UUID tenant, UUID id, boolean lock) {
        var rows = jdbc.query(SELECT + "WHERE e.tenant_id = ? AND e.id = ? AND e.deleted_at IS NULL" + (lock ? " FOR UPDATE OF e" : ""), ROW, tenant, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipamento não encontrado.");
        return rows.getFirst();
    }
    private void requireCustomer(UUID tenant, UUID id) {
        var rows = jdbc.query("SELECT id FROM public.customers WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tenant, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado nesta empresa.");
    }
    @Override public Equipment get(UUID id) { return execute("equipment:read", m -> read(m.tenantId(), id, false)); }
    @Override public Page search(String serial, UUID customerId, int page, int size) {
        if (serial == null || serial.length() > 150 || page < 0 || page > 1000000 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Busca ou paginação inválida.");
        return execute("equipment:read", m -> {
            var args = new java.util.ArrayList<Object>();
            args.add(m.tenantId()); args.add(serial.strip().toLowerCase(java.util.Locale.ROOT));
            String where = "WHERE e.tenant_id = ? AND e.deleted_at IS NULL AND strpos(lower(e.serial_number), ?) > 0";
            if (customerId != null) { where += " AND e.customer_id = ?"; args.add(customerId); }
            long total = jdbc.queryForObject("SELECT count(*) FROM public.equipment e " + where, Long.class, args.toArray());
            args.add(size); args.add(page * size);
            return new Page(jdbc.query(SELECT + where + " ORDER BY lower(e.model), e.id LIMIT ? OFFSET ?", ROW, args.toArray()), total, page, size);
        });
    }
    @Override public Equipment create(Equipment value) {
        return execute("equipment:write", m -> {
            requireCustomer(m.tenantId(), value.customerId());
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO public.equipment(id, tenant_id, customer_id, category, brand, model, serial_number, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    id, m.tenantId(), value.customerId(), value.category(), value.brand(), value.model(), value.serialNumber(), value.description());
            audit.record(m.userId(), "EQUIPMENT_CREATED", "EQUIPMENT", id, "{}");
            return read(m.tenantId(), id, false);
        });
    }
    @Override public Equipment update(UUID id, Equipment value) {
        return execute("equipment:write", m -> {
            read(m.tenantId(), id, true);
            requireCustomer(m.tenantId(), value.customerId());
            jdbc.update("UPDATE public.equipment SET customer_id = ?, category = ?, brand = ?, model = ?, serial_number = ?, description = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?",
                    value.customerId(), value.category(), value.brand(), value.model(), value.serialNumber(), value.description(), m.tenantId(), id);
            audit.record(m.userId(), "EQUIPMENT_UPDATED", "EQUIPMENT", id, "{}");
            return read(m.tenantId(), id, false);
        });
    }
    @Override public Equipment describe(UUID id, String description) {
        return execute("equipment:write_limited", m -> {
            read(m.tenantId(), id, true);
            jdbc.update("UPDATE public.equipment SET description = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?", description, m.tenantId(), id);
            audit.record(m.userId(), "EQUIPMENT_DESCRIPTION_UPDATED", "EQUIPMENT", id, "{}");
            return read(m.tenantId(), id, false);
        });
    }
    @Override public void delete(UUID id) {
        execute("equipment:write", m -> {
            read(m.tenantId(), id, true);
            jdbc.update("UPDATE public.equipment SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?", m.tenantId(), id);
            audit.record(m.userId(), "EQUIPMENT_DELETED", "EQUIPMENT", id, "{}");
            return null;
        });
    }
}
