package br.com.fixflow.modules.customers.infrastructure;

import br.com.fixflow.modules.customers.application.Customers;
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
import java.util.UUID;
import java.util.function.Function;

@Service
public class JdbcCustomers implements Customers {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    public JdbcCustomers(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity, AuditService audit) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.audit = audit;
    }
    private <T> T execute(boolean write, Function<AuthorizedMembership, T> action) {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenants.execute(tenant, member -> {
            if (!RolePermissions.forRole(member.role().name()).contains(write ? "customer:write" : "customer:read"))
                throw new AccessDeniedException("Acesso a clientes não autorizado.");
            return action.apply(member);
        });
    }
    private Customer read(UUID tenant, UUID id, boolean lock) {
        var rows = jdbc.query("SELECT id, name, document FROM public.customers WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Customer(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("document"), java.util.List.of()), tenant, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado.");
        var c = rows.getFirst();
        var contacts = jdbc.query("SELECT type, value, label FROM public.customer_contacts WHERE tenant_id = ? AND customer_id = ? ORDER BY position, id",
                (rs, row) -> new Customer.Contact(rs.getString("type"), rs.getString("value"), rs.getString("label")), tenant, id);
        return new Customer(c.id(), c.name(), c.document(), contacts);
    }
    @Override public Customer get(UUID id) { return execute(false, m -> read(m.tenantId(), id, false)); }
    @Override public Page search(String query, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100 || query == null || query.length() > 150)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginação ou busca inválida.");
        return execute(false, m -> {
            String q = query.strip().toLowerCase(java.util.Locale.ROOT);
            String digits = q.replaceAll("[^0-9]", "");
            String filter = """
                    FROM public.customers c WHERE c.tenant_id = ? AND c.deleted_at IS NULL AND
                    (? = '' OR strpos(lower(c.name), ?) > 0 OR
                      (? <> '' AND strpos(coalesce(c.document, ''), ?) > 0) OR EXISTS (
                        SELECT 1 FROM public.customer_contacts x WHERE x.tenant_id = c.tenant_id AND x.customer_id = c.id
                        AND x.type IN ('PHONE', 'WHATSAPP') AND ? <> ''
                        AND strpos(regexp_replace(x.value, '[^0-9]', '', 'g'), ?) > 0))
                    """;
            Object[] args = {m.tenantId(), q, q, digits, digits, digits, digits};
            long count = jdbc.queryForObject("SELECT count(*) " + filter, Long.class, args);
            var params = new java.util.ArrayList<Object>(java.util.Arrays.asList(args));
            params.add(size); params.add(page * size);
            var rows = jdbc.query("SELECT c.id, c.name, c.document " + filter + " ORDER BY lower(c.name), c.id LIMIT ? OFFSET ?",
                    (rs, row) -> new Customer(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("document"), java.util.List.of()), params.toArray());
            var byCustomer = new java.util.HashMap<UUID, java.util.List<Customer.Contact>>();
            if (!rows.isEmpty()) {
                var contactArgs = new java.util.ArrayList<Object>();
                contactArgs.add(m.tenantId());
                rows.forEach(c -> contactArgs.add(c.id()));
                String placeholders = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
                jdbc.query("SELECT customer_id, type, value, label FROM public.customer_contacts WHERE tenant_id = ? AND customer_id IN ("
                        + placeholders + ") ORDER BY customer_id, position, id", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID id = rs.getObject("customer_id", UUID.class);
                    byCustomer.computeIfAbsent(id, key -> new java.util.ArrayList<>())
                            .add(new Customer.Contact(rs.getString("type"), rs.getString("value"), rs.getString("label")));
                }, contactArgs.toArray());
            }
            return new Page(rows.stream().map(c -> new Customer(c.id(), c.name(), c.document(),
                    byCustomer.getOrDefault(c.id(), java.util.List.of()))).toList(), count, page, size);
        });
    }
    private void contacts(UUID tenant, UUID id, Customer customer) {
        for (int i = 0; i < customer.contacts().size(); i++) {
            var c = customer.contacts().get(i);
            if ("EMAIL".equals(c.type()) && !c.value().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail de contato inválido.");
            if (!"EMAIL".equals(c.type()) && (!c.value().matches("[+()0-9 .-]{7,30}") || !c.value().replaceAll("[^0-9]", "").matches("[0-9]{7,15}")))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Telefone de contato inválido.");
            jdbc.update("INSERT INTO public.customer_contacts(tenant_id, customer_id, type, value, label, position) VALUES (?, ?, ?, ?, ?, ?)",
                    tenant, id, c.type(), c.value(), c.label(), i);
        }
    }
    @Override public Customer create(Customer customer) {
        return execute(true, m -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO public.customers(id, tenant_id, name, document) VALUES (?, ?, ?, ?)", id, m.tenantId(), customer.name(), customer.document());
            contacts(m.tenantId(), id, customer);
            audit.record(m.userId(), "CUSTOMER_CREATED", "CUSTOMER", id, "{}");
            return read(m.tenantId(), id, false);
        });
    }
    @Override public Customer update(UUID id, Customer customer) {
        return execute(true, m -> {
            read(m.tenantId(), id, true);
            jdbc.update("UPDATE public.customers SET name = ?, document = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?", customer.name(), customer.document(), m.tenantId(), id);
            jdbc.update("DELETE FROM public.customer_contacts WHERE tenant_id = ? AND customer_id = ?", m.tenantId(), id);
            contacts(m.tenantId(), id, customer);
            audit.record(m.userId(), "CUSTOMER_UPDATED", "CUSTOMER", id, "{}");
            return read(m.tenantId(), id, false);
        });
    }
    @Override public void delete(UUID id) {
        execute(true, m -> {
            read(m.tenantId(), id, true);
            Boolean open = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.work_orders WHERE tenant_id = ? AND customer_id = ? AND status NOT IN ('ENTREGUE', 'CANCELADA'))", Boolean.class, m.tenantId(), id);
            if (Boolean.TRUE.equals(open)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cliente possui OS aberta. Finalize ou cancele as OS antes de excluir.");
            jdbc.update("UPDATE public.customers SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?", m.tenantId(), id);
            audit.record(m.userId(), "CUSTOMER_DELETED", "CUSTOMER", id, "{}");
            return null;
        });
    }
}
