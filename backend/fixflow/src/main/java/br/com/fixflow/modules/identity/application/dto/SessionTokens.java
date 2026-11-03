package br.com.fixflow.modules.identity.application.dto;

import br.com.fixflow.modules.identity.domain.SessionIdentity;
import java.time.Instant;

public record SessionTokens(String accessToken, String refreshToken, long expiresIn,
                            Instant refreshExpiresAt, SessionIdentity identity) {
    @Override
    public String toString() { return "SessionTokens[redacted]"; }
}
