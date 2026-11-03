package br.com.fixflow.modules.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshSession(UUID tokenId, UUID familyId, UUID userId, UUID tenantId,
                             Instant expiresAt, Instant absoluteExpiresAt, Instant usedAt, Instant revokedAt) {
    public boolean isUsableAt(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now) && absoluteExpiresAt.isAfter(now);
    }
}
