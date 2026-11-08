package br.com.fixflow.modules.inventory.infrastructure;

import br.com.fixflow.shared.infrastructure.tenant.TenantScope;
import br.com.fixflow.shared.infrastructure.tenant.TenantTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
public class InventoryReconciliationJob {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryReconciliationJob.class);
    private final JdbcTemplate jdbc;
    private final TenantTransaction tenants;
    private final TransactionTemplate transaction;

    public InventoryReconciliationJob(JdbcTemplate jdbc, TenantTransaction tenants, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void reconcileAll() {
        for (UUID tenant : jdbc.queryForList("SELECT * FROM public.inventory_reconciliation_tenants()", UUID.class)) {
            try {
                TenantScope.run(tenant, () -> transaction.executeWithoutResult(status -> {
                    tenants.configureCurrentTenant();
                    // Only one worker per tenant; lock is released on commit/rollback.
                    if (!Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 43043))", Boolean.class, tenant.toString()))) return;
                    reconcile(tenant);
                }));
            } catch (RuntimeException exception) {
                LOG.error("Inventory reconciliation failed for tenant {}", tenant, exception);
            }
        }
    }

    private void reconcile(UUID tenant) {
        // A single statement sees balances and committed movements in the same snapshot,
        // including during concurrent entries, consumption and returns. Inactive parts are checked too.
        int changed = jdbc.update("""
                WITH discrepancies AS MATERIALIZED (
                    SELECT p.tenant_id, p.id AS part_id, p.quantity_on_hand,
                        coalesce(sum(m.quantity), 0) AS movement_quantity
                    FROM public.parts p LEFT JOIN public.inventory_movements m
                        ON m.tenant_id = p.tenant_id AND m.part_id = p.id
                    WHERE p.tenant_id = ?
                    GROUP BY p.tenant_id, p.id, p.quantity_on_hand
                    HAVING coalesce(sum(m.quantity), 0) <> p.quantity_on_hand
                ), opened AS (
                    INSERT INTO public.inventory_reconciliation_alerts(tenant_id, part_id, movement_quantity, quantity_on_hand)
                    SELECT tenant_id, part_id, movement_quantity, quantity_on_hand FROM discrepancies
                    ON CONFLICT (tenant_id, part_id) WHERE resolved_at IS NULL DO UPDATE
                        SET movement_quantity = EXCLUDED.movement_quantity, quantity_on_hand = EXCLUDED.quantity_on_hand,
                            last_checked_at = clock_timestamp()
                    RETURNING id
                )
                UPDATE public.inventory_reconciliation_alerts a SET resolved_at = clock_timestamp(), last_checked_at = clock_timestamp()
                WHERE a.tenant_id = ? AND a.resolved_at IS NULL
                    AND NOT EXISTS (SELECT 1 FROM discrepancies d WHERE d.part_id = a.part_id)
                """, tenant, tenant);
        LOG.info("Inventory reconciliation completed for tenant {}; resolved alerts: {}", tenant, changed);
    }
}
