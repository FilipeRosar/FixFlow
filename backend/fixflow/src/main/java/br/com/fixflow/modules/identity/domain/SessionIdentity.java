package br.com.fixflow.modules.identity.domain;

import java.util.UUID;

public record SessionIdentity(UUID userId, String email, UUID tenantId, String role) {}
