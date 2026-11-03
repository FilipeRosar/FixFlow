package br.com.fixflow.shared.infrastructure.security;


import java.util.Objects;
import java.util.UUID;

public record AuthenticatedIdentity(
        UUID userId,
        String email,
        UUID tenantId
) {
    public AuthenticatedIdentity(UUID userId, String email) { this(userId, email, null); }

    public AuthenticatedIdentity {
        Objects.requireNonNull(userId, "O userId é obrigatório.");
        Objects.requireNonNull(email, "O email é obrigatório.");

        if (email.isBlank()) {
            throw new IllegalArgumentException(
                    "O email não pode estar vazio."
            );
        }
    }
}