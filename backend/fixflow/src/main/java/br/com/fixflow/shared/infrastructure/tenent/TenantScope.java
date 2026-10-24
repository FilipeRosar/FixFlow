package br.com.fixflow.shared.infrastructure.tenent;

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
        UUID previousTenant = TenentContext.getTenantId().orElse(null);

        try {
            TenentContext.setTenantId(tenantId);
            runnable.run();
        } finally {
            if (previousTenant == null) {
                TenentContext.clearTenantId();
            } else  {
                TenentContext.setTenantId(previousTenant);
            }
        }
    }
}
