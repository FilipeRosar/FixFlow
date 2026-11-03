package br.com.fixflow.modules.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record LoginAccount(UUID userId, String email, String passwordHash, String status, Instant lockedUntil) {
    public boolean canAuthenticateAt(Instant now) {
        return "ACTIVE".equals(status) && (lockedUntil == null || !lockedUntil.isAfter(now));
    }

    @Override
    public String toString() { return "LoginAccount[redacted]"; }
}
