package br.com.fixflow.shared.application.registration.dto;

import java.util.UUID;

public record RegisteredAccount(UUID userId, UUID tenantId, String role) {}
