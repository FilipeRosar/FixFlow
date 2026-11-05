package br.com.fixflow.modules.customers.infrastructure;

import br.com.fixflow.modules.customers.application.CustomerHistory;
import br.com.fixflow.modules.customers.domain.CustomerHistoryEntry;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.List;

@Service
public class JdbcCustomerHistory implements CustomerHistory {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    public JdbcCustomerHistory(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity;
    }
    @Override public Page load(UUID customerId, String type, int page, int size, String sort) {
        if (!List.of("ALL", "WORK_ORDER", "EQUIPMENT").contains(type) || !List.of("asc", "desc").contains(sort)
                || page < 0 || page > 1000000 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtros ou paginação inválidos.");
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenants.execute(tenant, member -> {
            var permissions = RolePermissions.forRole(member.role().name());
            if (!permissions.contains("customer:read")) throw new AccessDeniedException("Acesso a clientes não autorizado.");
            boolean orders = permissions.contains("workorder:read");
            boolean equipment = permissions.contains("equipment:read");
            if ((type.equals("WORK_ORDER") && !orders) || (type.equals("EQUIPMENT") && !equipment))
                throw new AccessDeniedException("Acesso ao histórico não autorizado.");
            var customers = jdbc.queryForList("SELECT name, anonymized_at FROM public.customers WHERE tenant_id = ? AND id = ? AND (deleted_at IS NULL OR anonymized_at IS NOT NULL)", tenant, customerId);
            if (customers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado.");
            var selects = new java.util.ArrayList<String>();
            var args = new java.util.ArrayList<Object>();
            if (orders && !type.equals("EQUIPMENT")) {
                selects.add("""
                        SELECT id, 'WORK_ORDER' AS type, created_at, 'Ordem de serviço' AS title,
                               status, NULL::text AS serial_number, false AS deleted
                        FROM public.work_orders WHERE tenant_id = ? AND customer_id = ?
                        """);
                args.add(tenant); args.add(customerId);
            }
            if (equipment && !type.equals("WORK_ORDER")) {
                selects.add("""
                        SELECT id, 'EQUIPMENT' AS type, created_at,
                               concat_ws(' ', nullif(brand, ''), model) AS title,
                               NULL::text AS status, serial_number, deleted_at IS NOT NULL AS deleted
                        FROM public.equipment WHERE tenant_id = ? AND customer_id = ?
                        """);
                args.add(tenant); args.add(customerId);
            }
            if (selects.isEmpty()) throw new AccessDeniedException("Acesso ao histórico não autorizado.");
            String from = " FROM (" + String.join(" UNION ALL ", selects) + ") history";
            long total = jdbc.queryForObject("SELECT count(*)" + from, Long.class, args.toArray());
            args.add(size); args.add(page * size);
            String direction = sort.equals("asc") ? " ASC" : " DESC";
            var content = jdbc.query("SELECT *" + from + " ORDER BY created_at" + direction + ", type" + direction + ", id" + direction + " LIMIT ? OFFSET ?",
                    (rs, row) -> new CustomerHistoryEntry(rs.getObject("id", UUID.class), rs.getString("type"),
                            rs.getTimestamp("created_at").toInstant(), rs.getString("title"), rs.getString("status"),
                            rs.getString("serial_number"), rs.getBoolean("deleted")), args.toArray());
            return new Page(customerId, (String) customers.getFirst().get("name"), content, total, page, size, sort, orders, customers.getFirst().get("anonymized_at") != null);
        });
    }
}
