package br.com.fixflow.modules.customers.infrastructure;

import br.com.fixflow.modules.customers.application.CustomerPrivacy;
import br.com.fixflow.modules.customers.domain.CustomerExport;
import br.com.fixflow.modules.customers.domain.Customer;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

@Service
public class JdbcCustomerPrivacy implements CustomerPrivacy {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    public JdbcCustomerPrivacy(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity, AuditService audit) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.audit = audit;
    }
    private <T> T execute(UUID customer, Function<AuthorizedMembership, T> action) {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenants.execute(tenant, m -> {
            if (!RolePermissions.forRole(m.role().name()).contains("customer:privacy"))
                throw new AccessDeniedException("Ação disponível somente ao proprietário.");
            var rows = jdbc.query("SELECT id FROM public.customers WHERE tenant_id = ? AND id = ? FOR UPDATE",
                    (rs, row) -> rs.getObject("id", UUID.class), tenant, customer);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado.");
            return action.apply(m);
        });
    }
    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    @Override public CustomerExport export(UUID id) {
        return execute(id, m -> {
            var contacts = jdbc.query("SELECT type, value, label FROM public.customer_contacts WHERE tenant_id = ? AND customer_id = ? ORDER BY position, id",
                    (rs, row) -> new Customer.Contact(rs.getString("type"), rs.getString("value"), rs.getString("label")), m.tenantId(), id);
            var profile = jdbc.queryForObject("SELECT * FROM public.customers WHERE tenant_id = ? AND id = ?",
                    (rs, row) -> new CustomerExport.Profile(id, rs.getString("name"), rs.getString("document"), instant(rs, "created_at"),
                            instant(rs, "updated_at"), instant(rs, "deleted_at"), instant(rs, "anonymized_at"), contacts), m.tenantId(), id);
            var equipment = jdbc.query("SELECT * FROM public.equipment WHERE tenant_id = ? AND customer_id = ? ORDER BY created_at, id",
                    (rs, row) -> new CustomerExport.ExportEquipment(rs.getObject("id", UUID.class), rs.getString("category"), rs.getString("brand"), rs.getString("model"),
                            rs.getString("serial_number"), rs.getString("description"), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "deleted_at")), m.tenantId(), id);
            var orders = jdbc.query("SELECT id, status, created_at FROM public.work_orders WHERE tenant_id = ? AND customer_id = ? ORDER BY created_at, id",
                    (rs, row) -> new CustomerExport.ExportOrder(rs.getObject("id", UUID.class), rs.getString("status"), instant(rs, "created_at")), m.tenantId(), id);
            audit.record(m.userId(), "CUSTOMER_EXPORTED", "CUSTOMER", id, "{}");
            return new CustomerExport(1, Instant.now(), profile, equipment, orders);
        });
    }
    @Override public void anonymize(UUID id) {
        execute(id, m -> {
            Boolean already = jdbc.queryForObject("SELECT anonymized_at IS NOT NULL FROM public.customers WHERE tenant_id = ? AND id = ?", Boolean.class, m.tenantId(), id);
            if (Boolean.TRUE.equals(already)) return null;
            Boolean open = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.work_orders WHERE tenant_id = ? AND customer_id = ? AND status NOT IN ('ENTREGUE', 'CANCELADA'))", Boolean.class, m.tenantId(), id);
            if (Boolean.TRUE.equals(open)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cliente possui OS aberta. Finalize ou cancele as OS antes de anonimizar.");
            jdbc.update("DELETE FROM public.customer_contacts WHERE tenant_id = ? AND customer_id = ?", m.tenantId(), id);
            jdbc.update("UPDATE public.equipment SET brand = '', model = 'Equipamento anonimizado', serial_number = '', description = '', deleted_at = coalesce(deleted_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND customer_id = ?", m.tenantId(), id);
            jdbc.update("UPDATE public.customers SET name = 'Cliente anonimizado', document = NULL, anonymized_at = CURRENT_TIMESTAMP, deleted_at = coalesce(deleted_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?", m.tenantId(), id);
            audit.record(m.userId(), "CUSTOMER_ANONYMIZED", "CUSTOMER", id, "{}");
            return null;
        });
    }
}
