package br.com.fixflow.shared.infrastructure.tenent;

import java.util.Optional;
import java.util.UUID;

public final class TenentContext {
    private static final ThreadLocal<UUID> CURRENT_TENENT =  new ThreadLocal<>();

    private TenentContext() {}

    public static void setTenantId(UUID tenantId) {
        if(tenantId == null) {
            throw new IllegalArgumentException("O tenant não pode ser nulo.");
        }
        CURRENT_TENENT.set(tenantId);
    }
    public static Optional<UUID> getTenantId() {
        return Optional.ofNullable(CURRENT_TENENT.get());
    }
    public static UUID requireTenantId() {
        return getTenantId().orElseThrow(() -> new IllegalArgumentException(
                "Nenhum tenant está definido no contexto atual."
        ));
    }
    public static void clearTenantId() {
        CURRENT_TENENT.remove();
    }
}
