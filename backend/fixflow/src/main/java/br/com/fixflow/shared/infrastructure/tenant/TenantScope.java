package br.com.fixflow.shared.infrastructure.tenant;

import java.util.UUID;

public final class TenantScope {
    private TenantScope() {}

    public static void run(UUID tenantId, Runnable runnable) {
        if(tenantId == null) {
            throw new IllegalArgumentException("O tenantId não pode ser nulo.");
        }
        if (runnable == null) {
            throw new IllegalArgumentException("A tarefa não pode ser nula");
        }
        UUID previousTenant = TenantContext.getTenantId().orElse(null);

        try {
            TenantContext.setTenantId(tenantId);
            runnable.run();
        } finally {
            if (previousTenant == null) {
                TenantContext.clearTenantId();
            } else  {
                TenantContext.setTenantId(previousTenant);
            }
        }
    }
}
