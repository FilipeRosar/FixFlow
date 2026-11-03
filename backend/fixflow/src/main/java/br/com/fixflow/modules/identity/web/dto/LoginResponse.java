package br.com.fixflow.modules.identity.web.dto;

import java.util.UUID;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, UUID userId, UUID tenantId, String role) {
    @Override
    public String toString() { return "LoginResponse[redacted]"; }
}
