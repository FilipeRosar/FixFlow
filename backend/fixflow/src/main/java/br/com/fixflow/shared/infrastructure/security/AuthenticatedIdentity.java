package br.com.fixflow.shared.infrastructure.security;


import java.util.Objects;
import java.util.UUID;

public record AuthenticatedIdentity(
        UUID userId,
        String email
) {
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