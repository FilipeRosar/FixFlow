package br.com.fixflow.modules.inventory.infrastructure;

import br.com.fixflow.modules.inventory.application.*;
import br.com.fixflow.modules.inventory.domain.Part;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.modules.tenants.domain.InsufficientStockException;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;

@Service
public class JdbcInventory implements Parts, StockOperations {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    public JdbcInventory(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity, AuditService audit) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.audit = audit;
    }
    private UUID tenant() {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new org.springframework.security.authentication.InsufficientAuthenticationException("Selecione uma empresa.");
        return tenant;
    }
    private static final RowMapper<Part> ROW = (rs, n) -> new Part(rs.getObject("id", UUID.class), rs.getString("sku"),
            rs.getString("name"), rs.getString("unit"), rs.getBigDecimal("cost"), rs.getBigDecimal("sale_price"),
            rs.getBigDecimal("quantity_on_hand"), rs.getBigDecimal("min_quantity"), rs.getBoolean("active"), rs.getInt("version"));
    @Override public List<Part> list() {
        return tenants.execute(tenant(), m -> {
            if (!RolePermissions.forRole(m.role().name()).contains("inventory:read")) throw new AccessDeniedException("Consulta de peças não autorizada.");
            return jdbc.query("SELECT * FROM public.parts WHERE tenant_id = ? AND active ORDER BY name, id", ROW, m.tenantId());
        });
    }
    @Override public Part create(String sku, String name, String unit, BigDecimal cost, BigDecimal price, BigDecimal minimum) {
        return tenants.execute(tenant(), m -> {
            if (!RolePermissions.forRole(m.role().name()).contains("inventory:write")) throw new AccessDeniedException("Cadastro de peças não autorizado.");
            UUID id = UUID.randomUUID();
            var rows = jdbc.query("""
                    INSERT INTO public.parts(id, tenant_id, sku, name, unit, cost, sale_price, min_quantity)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, sku) DO NOTHING RETURNING *
                    """, ROW, id, m.tenantId(), sku, name, unit, cost, price, minimum);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU já cadastrado nesta empresa.");
            audit.record(m.userId(), "PART_CREATED", "PART", id, "{}");
            return rows.getFirst();
        });
    }
    @Override public Part get(UUID id) {
        return tenants.execute(tenant(), m -> {
            requirePermission(m.role().name(), "inventory:read");
            return find(m.tenantId(), id);
        });
    }
    private void requirePermission(String role, String permission) {
        if (!RolePermissions.forRole(role).contains(permission)) throw new AccessDeniedException("Operação de peças não autorizada.");
    }
    private Part find(UUID tenant, UUID id) {
        var rows = jdbc.query("SELECT * FROM public.parts WHERE tenant_id = ? AND id = ? AND active", ROW, tenant, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Peça não encontrada.");
        return rows.getFirst();
    }
    @Override public Part update(UUID id, String sku, String name, String unit, BigDecimal cost, BigDecimal price, BigDecimal minimum, int version) {
        try {
            return tenants.execute(tenant(), m -> {
                requirePermission(m.role().name(), "inventory:write");
                find(m.tenantId(), id);
                var rows = jdbc.query("""
                        UPDATE public.parts SET sku = ?, name = ?, unit = ?, cost = ?, sale_price = ?,
                            min_quantity = ?, version = version + 1
                        WHERE tenant_id = ? AND id = ? AND active AND version = ? RETURNING *
                        """, ROW, sku, name, unit, cost, price, minimum, m.tenantId(), id, version);
                if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Peça alterada. Atualize os dados.");
                audit.record(m.userId(), "PART_UPDATED", "PART", id, "{}");
                return rows.getFirst();
            });
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU já cadastrado nesta empresa.");
        }
    }
    @Override public void delete(UUID id, int version) {
        tenants.execute(tenant(), m -> {
            requirePermission(m.role().name(), "inventory:write");
            find(m.tenantId(), id);
            int changed = jdbc.update("""
                    UPDATE public.parts SET active = false, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND active AND version = ?
                    """, m.tenantId(), id, version);
            if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Peça alterada. Atualize os dados.");
            audit.record(m.userId(), "PART_DELETED", "PART", id, "{}");
            return null;
        });
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Part consume(UUID tenant, UUID actor, UUID part, UUID order, UUID item, BigDecimal quantity) {
        var exists = jdbc.queryForList("SELECT id FROM public.parts WHERE tenant_id = ? AND id = ? AND active", tenant, part);
        if (exists.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Peça não encontrada ou inativa.");
        var rows = jdbc.query("""
                UPDATE public.parts SET quantity_on_hand = quantity_on_hand - ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND active AND (quantity_on_hand >= ? OR
                    coalesce((SELECT allow_negative_stock FROM public.tenant_settings WHERE tenant_id = ?), false)) RETURNING *
                """, ROW, quantity, tenant, part, quantity, tenant);
        if (rows.isEmpty()) throw new InsufficientStockException();
        Part updated = rows.getFirst();
        movement(tenant, actor, part, order, item, "EXIT", quantity.negate(), updated.cost());
        return updated;
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void restore(UUID tenant, UUID actor, UUID part, UUID order, UUID item, BigDecimal quantity, BigDecimal cost) {
        if (jdbc.update("UPDATE public.parts SET quantity_on_hand = quantity_on_hand + ?, version = version + 1 WHERE tenant_id = ? AND id = ?", quantity, tenant, part) != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Peça não encontrada.");
        movement(tenant, actor, part, order, item, "RETURN", quantity, cost);
    }
    private void movement(UUID tenant, UUID actor, UUID part, UUID order, UUID item, String type, BigDecimal quantity, BigDecimal cost) {
        jdbc.update("""
                INSERT INTO public.inventory_movements(id, tenant_id, part_id, work_order_id, item_id, type, quantity, unit_cost, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenant, part, order, item, type, quantity, cost, actor);
    }
}
