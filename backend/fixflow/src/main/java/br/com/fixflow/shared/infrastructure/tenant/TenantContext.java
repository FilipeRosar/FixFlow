
package br.com.fixflow.shared.infrastructure.tenant;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT =
            new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "O tenant não pode ser nulo."
            );
        }

        CURRENT_TENANT.set(tenantId);
        MDC.put("tenantId", tenantId.toString());
    }

    public static Optional<UUID> getTenantId() {
        return Optional.ofNullable(CURRENT_TENANT.get());
    }

    public static UUID requireTenantId() {
        return getTenantId().orElseThrow(
                () -> new IllegalStateException(
                        "Nenhum tenant está definido no contexto atual."
                )
        );
    }

    public static void clearTenantId() {
        CURRENT_TENANT.remove();
        MDC.remove("tenantId");
    }
}
