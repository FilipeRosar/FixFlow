
package br.com.fixflow.shared.application.tenant;

import java.util.Objects;
import java.util.UUID;

public record AuthorizedMembership(
        UUID userId,
        UUID tenantId,
        Role role
) {
    public AuthorizedMembership {
        Objects.requireNonNull(userId, "userId obrigatório");
        Objects.requireNonNull(tenantId, "tenantId obrigatório");
        Objects.requireNonNull(role, "role obrigatória");
    }

    public enum Role {
        OWNER,
        ADMIN,
        TECHNICIAN,
        VIEWER
    }
}
